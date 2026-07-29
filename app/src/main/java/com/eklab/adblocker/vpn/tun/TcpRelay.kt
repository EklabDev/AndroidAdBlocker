package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.vpn.parse.TlsSniParser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal userspace TCP relay for one flow (NetGuard-style, simplified).
 *
 * Data flow:
 * - The packet loop hands client packets to [onSyn] / [onTunPacket] (loop thread).
 * - We answer the handshake ourselves (SYN -> SYN/ACK with our own ISN). For
 *   TCP/443 and TCP/80 the upstream connect is deferred until metadata (SNI /
 *   Host header) is extracted from the first payload so the rule engine sees
 *   a hostname; early payload is buffered and ACKed, then replayed upstream
 *   once connected. For other ports the verdict is computed at SYN time.
 * - After connect, payload is shuttled both ways with 1:1 sequence-number
 *   translation: client seq numbers are tracked in [clientNext], our side
 *   uses [ourNext] as seq towards the client. A reader thread (executor)
 *   blocks on the upstream socket and writes IPv4+TCP packets into the TUN.
 *
 * Deliberate simplifications (documented v1 scope):
 * - No out-of-order buffering: a segment with `seq != clientNext` is dropped
 *   and answered with a duplicate ACK; the client retransmits.
 * - No congestion control, window scaling, SACK or TCP options (fixed 64 KiB
 *   window, no MSS option — clients fall back to small segments).
 * - Upstream reads are clamped to [CHUNK] bytes per packet (our de facto MSS).
 * - Blocked flows and refused connections are answered with RST.
 *
 * Threading: [onSyn]/[onTunPacket] run on the packet-loop thread only;
 * connect callbacks and [readerLoop] run on executor threads. Shared seq
 * state is `@Volatile`; control packets are built in per-context scratch
 * buffers so no allocation happens per data ACK.
 */
class TcpRelay(
    val base: FlowBase,
    private val tunWriter: TunWriter,
    private val evaluator: VerdictEvaluator,
    private val protect: (Socket) -> Boolean,
    private val executor: ExecutorService,
    private val onClosed: (TcpRelay) -> Unit,
) {

    private enum class State { SYN, AWAITING_METADATA, CONNECTING, ESTABLISHED, CLOSED }

    private val key = base.key

    @Volatile private var state = State.SYN
    @Volatile private var clientNext = 0
    private var clientSynSeq = 0
    private val ourIsn = ThreadLocalRandom.current().nextInt()
    @Volatile private var ourNext = ourIsn
    @Volatile private var synAckSent = false
    @Volatile private var clientClosed = false
    @Volatile private var serverClosed = false

    /** True once the allow/block verdict for this flow has been computed. */
    private var decided = false

    /** Client payload buffered before the upstream connection exists. */
    private val pending = ByteArrayOutputStream(INITIAL_PENDING)

    private var channel: SocketChannel? = null
    private var sniParser: TlsSniParser? = if (key.dstPort == HTTPS_PORT) TlsSniParser() else null
    private var hostScanned = false

    private val closedOnce = AtomicBoolean(false)

    /** Scratch for control packets built on the loop thread (no per-ACK allocation). */
    private val loopScratch = ByteArray(CONTROL_PACKET_MAX)

    val isTerminated: Boolean get() = state == State.CLOSED

    // ------------------------------------------------------------------
    // Loop-thread entry points
    // ------------------------------------------------------------------

    /** First packet of the flow (a bare SYN). */
    fun onSyn(seq: Int) {
        base.touch()
        clientSynSeq = seq
        clientNext = seq + 1
        if (key.dstPort == HTTPS_PORT || key.dstPort == HTTP_PORT) {
            // Complete the handshake locally, decide once SNI/Host is known.
            state = State.AWAITING_METADATA
            sendSynAckLoop()
        } else if (evaluator.evaluate(base)) {
            sendRstLoop()
            terminate()
        } else {
            state = State.CONNECTING
            connectUpstream(sendSynAckAfter = true)
        }
    }

    /** Subsequent client packets. [payloadOff]/[payloadLen] index into the loop's read buffer. */
    fun onTunPacket(flags: Int, seq: Int, buf: ByteArray, payloadOff: Int, payloadLen: Int) {
        if (isTerminated) return
        base.touch()

        if (flags and Packets.TCP_RST != 0) {
            clientClosed = true
            closeChannel()
            terminate()
            return
        }

        when (state) {
            State.SYN, State.CLOSED -> Unit
            State.AWAITING_METADATA, State.CONNECTING -> {
                if (flags and Packets.TCP_SYN != 0) {
                    // SYN retransmit while we are still busy: re-answer if we already did.
                    if (seq == clientSynSeq && synAckSent) sendSynAckLoop()
                    return
                }
                if (seq != clientNext) {
                    sendAckLoop()
                    return
                }
                var advance = payloadLen
                if (payloadLen > 0) {
                    pending.write(buf, payloadOff, payloadLen)
                    base.bytesSent.addAndGet(payloadLen.toLong())
                    if (state == State.AWAITING_METADATA) {
                        feedMetadata(buf, payloadOff, payloadLen)
                        if (isTerminated) return // blocked by the verdict
                        if (!decided && pending.size() > MAX_PENDING_BEFORE_DECIDE) decide()
                    }
                }
                if (flags and Packets.TCP_FIN != 0) {
                    advance += 1
                    clientClosed = true
                }
                clientNext += advance
                sendAckLoop()
                if (clientClosed && serverClosed) terminate()
            }
            State.ESTABLISHED -> {
                if (seq != clientNext) {
                    sendAckLoop()
                    return
                }
                var advance = payloadLen
                if (payloadLen > 0) {
                    if (!writeUpstream(buf, payloadOff, payloadLen)) {
                        sendRstLoop()
                        terminate()
                        return
                    }
                    base.bytesSent.addAndGet(payloadLen.toLong())
                }
                if (flags and Packets.TCP_FIN != 0) {
                    advance += 1
                    clientClosed = true
                    shutdownChannelOutput()
                }
                clientNext += advance
                // Only ACK segments that carry new sequence numbers; replying to a
                // bare ACK would just generate chatter.
                if (payloadLen > 0 || flags and Packets.TCP_FIN != 0) sendAckLoop()
                if (clientClosed && serverClosed) terminate()
            }
        }
    }

    // ------------------------------------------------------------------
    // Metadata extraction (loop thread, AWAITING_METADATA only)
    // ------------------------------------------------------------------

    private fun feedMetadata(buf: ByteArray, off: Int, len: Int) {
        if (decided) return
        when (key.dstPort) {
            HTTPS_PORT -> {
                val parser = sniParser
                if (parser == null) {
                    decide()
                    return
                }
                // TlsSniParser consumes from offset 0; handshake-size copies only.
                parser.feed(buf.copyOfRange(off, off + len))
                if (parser.isFinished || parser.isFailed) {
                    base.sni = parser.sni
                    sniParser = null
                    decide()
                }
            }
            HTTP_PORT -> {
                if (!hostScanned) {
                    hostScanned = true
                    base.httpHost = extractHostHeader(buf, off, len)
                    decide()
                }
            }
            else -> decide()
        }
    }

    /** Pulls the Host header out of the first request bytes; `null` when absent. */
    private fun extractHostHeader(buf: ByteArray, off: Int, len: Int): String? {
        val text = String(buf, off, minOf(len, MAX_HTTP_SCAN), Charsets.ISO_8859_1)
        val match = HOST_HEADER.find(text) ?: return null
        return match.groupValues[1].trim().substringBefore(':').lowercase().ifEmpty { null }
    }

    /** Computes the verdict once metadata is in; blocks or starts the upstream connect. */
    private fun decide() {
        if (decided) return
        decided = true
        if (evaluator.evaluate(base)) {
            sendRstLoop()
            terminate()
        } else {
            state = State.CONNECTING
            connectUpstream(sendSynAckAfter = false)
        }
    }

    // ------------------------------------------------------------------
    // Upstream connection (executor threads)
    // ------------------------------------------------------------------

    private fun connectUpstream(sendSynAckAfter: Boolean) {
        executor.execute {
            val ch = try {
                SocketChannel.open().also { c ->
                    c.configureBlocking(true)
                    if (!protect(c.socket())) throw java.io.IOException("VpnService.protect failed")
                    c.socket().connect(
                        InetSocketAddress(InetAddress.getByAddress(Packets.ipToBytes(key.dstIp)), key.dstPort),
                        CONNECT_TIMEOUT_MS,
                    )
                    c.socket().tcpNoDelay = true
                }
            } catch (t: Throwable) {
                // Connection refused / unreachable / protect failed: tell the client.
                sendRstOneShot()
                terminate()
                return@execute
            }
            synchronized(this) {
                if (isTerminated) {
                    try {
                        ch.close()
                    } catch (t: Throwable) {
                        // ignore
                    }
                    return@execute
                }
                channel = ch
                if (sendSynAckAfter) sendSynAckOneShot()
                // Replay buffered pre-connect payload *before* going ESTABLISHED so a
                // new client segment can't overtake it on the upstream socket.
                flushPendingLocked()
                if (isTerminated) return@execute // flush failed; RST already sent
                state = State.ESTABLISHED
                if (clientClosed) shutdownChannelOutput()
            }
            executor.execute { readerLoop(ch) }
        }
    }

    /** Replays buffered pre-connect payload upstream. Caller holds `synchronized(this)`. */
    private fun flushPendingLocked() {
        if (pending.size() == 0) return
        val bytes = pending.toByteArray()
        pending.reset()
        if (!writeUpstream(bytes, 0, bytes.size)) {
            sendRstOneShot()
            terminate()
        }
    }

    /** Blocking write of one client segment upstream; `false` on failure. */
    private fun writeUpstream(buf: ByteArray, off: Int, len: Int): Boolean {
        val ch = channel ?: return false
        return try {
            val bb = ByteBuffer.wrap(buf, off, len)
            while (bb.hasRemaining()) ch.write(bb)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Server -> client direction: block on the socket, emit TCP segments into the TUN. */
    private fun readerLoop(ch: SocketChannel) {
        // Header space + one MSS-sized chunk per packet.
        val rbuf = ByteArray(Packets.IPV4_HEADER_LEN + Packets.TCP_HEADER_LEN + CHUNK)
        while (!isTerminated && !serverClosed) {
            val n = try {
                ch.read(ByteBuffer.wrap(rbuf, Packets.IPV4_HEADER_LEN + Packets.TCP_HEADER_LEN, CHUNK))
            } catch (t: Throwable) {
                -1
            }
            when {
                n > 0 -> {
                    base.touch()
                    base.bytesReceived.addAndGet(n.toLong())
                    val seq = ourNext
                    ourNext += n
                    val packetLen = Packets.buildTcpPacket(
                        rbuf, key.dstIp, key.dstPort, key.srcIp, key.srcPort,
                        seq = seq, ack = clientNext, flags = Packets.TCP_ACK or Packets.TCP_PSH,
                        window = WINDOW, payload = rbuf,
                        payloadOff = Packets.IPV4_HEADER_LEN + Packets.TCP_HEADER_LEN, payloadLen = n,
                    )
                    tunWriter.write(rbuf, packetLen)
                }
                else -> {
                    // EOF or error: close the server->client direction.
                    serverClosed = true
                    val seq = ourNext
                    ourNext += 1
                    val packetLen = Packets.buildTcpPacket(
                        rbuf, key.dstIp, key.dstPort, key.srcIp, key.srcPort,
                        seq = seq, ack = clientNext, flags = Packets.TCP_FIN or Packets.TCP_ACK,
                        window = WINDOW,
                    )
                    tunWriter.write(rbuf, packetLen)
                    if (clientClosed) terminate()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Control packets
    // ------------------------------------------------------------------

    private fun buildControl(out: ByteArray, seq: Int, ack: Int, flags: Int): Int =
        Packets.buildTcpPacket(
            out, key.dstIp, key.dstPort, key.srcIp, key.srcPort,
            seq = seq, ack = ack, flags = flags, window = WINDOW,
        )

    /** SYN/ACK from the loop thread (reuses [loopScratch]). */
    private fun sendSynAckLoop() {
        ourNext = ourIsn + 1
        val len = buildControl(loopScratch, ourIsn, clientNext, Packets.TCP_SYN or Packets.TCP_ACK)
        synAckSent = true
        tunWriter.write(loopScratch, len)
    }

    /** SYN/ACK from the connect thread (fresh buffer; rare path). */
    private fun sendSynAckOneShot() {
        ourNext = ourIsn + 1
        val buf = ByteArray(CONTROL_PACKET_MAX)
        val len = buildControl(buf, ourIsn, clientNext, Packets.TCP_SYN or Packets.TCP_ACK)
        synAckSent = true
        tunWriter.write(buf, len)
    }

    /** Pure ACK of whatever both sides have acknowledged so far (loop thread). */
    private fun sendAckLoop() {
        val len = buildControl(loopScratch, ourNext, clientNext, Packets.TCP_ACK)
        tunWriter.write(loopScratch, len)
    }

    /** RST,ACK in response to the client's current position (loop thread). */
    private fun sendRstLoop() {
        val len = buildControl(loopScratch, ourNext, clientNext, Packets.TCP_RST or Packets.TCP_ACK)
        tunWriter.write(loopScratch, len)
    }

    /** RST,ACK from a non-loop thread (fresh buffer; rare path). */
    private fun sendRstOneShot() {
        val buf = ByteArray(CONTROL_PACKET_MAX)
        val len = buildControl(buf, ourNext, clientNext, Packets.TCP_RST or Packets.TCP_ACK)
        tunWriter.write(buf, len)
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private fun shutdownChannelOutput() {
        try {
            channel?.shutdownOutput()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun closeChannel() {
        try {
            channel?.close()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Force-close (idle reap, shutdown). No packets are sent to the client. */
    fun abort() {
        closeChannel()
        terminate()
    }

    /** Idempotent terminal transition: closes upstream and notifies the tracker once. */
    private fun terminate() {
        if (!closedOnce.compareAndSet(false, true)) return
        state = State.CLOSED
        closeChannel()
        onClosed(this)
    }

    companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val CONNECT_TIMEOUT_MS = 10_000
        const val WINDOW = 65535
        /** Max payload per server->client segment (our de facto MSS). */
        const val CHUNK = 1400
        const val CONTROL_PACKET_MAX = 64
        const val INITIAL_PENDING = 4096
        /** Give up waiting for metadata beyond this much buffered payload. */
        const val MAX_PENDING_BEFORE_DECIDE = 16 * 1024
        const val MAX_HTTP_SCAN = 2048
        val HOST_HEADER = Regex("(?im)^host:\\s*([^\\r\\n]+)")
    }
}
