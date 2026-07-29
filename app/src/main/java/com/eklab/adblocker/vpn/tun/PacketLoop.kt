package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.vpn.parse.DnsParser
import com.eklab.adblocker.vpn.parse.IpHostnameCache
import java.io.FileInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ExecutorService

/**
 * The TUN reader: a single dedicated thread ("tun-loop") doing blocking reads
 * on the TUN file descriptor into one reused 32 KiB buffer — no allocation
 * per packet.
 *
 * Per packet it parses the IPv4 header, ignores anything that is not TCP/UDP,
 * demultiplexes into [FlowTracker] flows and:
 * - UDP: creates a [UdpRelay] (protected, connected socket) for allowed flows,
 *   parses DNS queries for the flow's hostname, drops blocked flows.
 * - TCP: creates a [TcpRelay] on SYN and forwards segments to it; answers
 *   packets for unknown flows with RST so apps fail fast instead of retrying.
 *
 * The loop exits when [stop] flips the running flag and the TUN fd is closed
 * by the caller (the blocked read then throws).
 */
class PacketLoop(
    private val input: FileInputStream,
    private val tunWriter: TunWriter,
    private val tracker: FlowTracker,
    private val evaluator: VerdictEvaluator,
    private val ipHostnameCache: IpHostnameCache,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val protectTcp: (Socket) -> Boolean,
    private val executor: ExecutorService,
) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "tun-loop").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops the loop. The caller must close the TUN fd (which unblocks the
     * read) before or while calling this; we then wait for the thread to exit.
     */
    fun stopAndJoin(timeoutMs: Long = 2_000L) {
        running = false
        try {
            thread?.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
    }

    private fun run() {
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                break // TUN closed (shutdown / revoke)
            }
            if (n <= 0) continue
            try {
                handlePacket(buf, n)
            } catch (t: Throwable) {
                // A malformed packet or a relay hiccup must never kill the loop.
            }
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        if (n < Packets.IPV4_HEADER_LEN) return
        if (Packets.ipVersion(buf) != 4) return
        val ihl = Packets.ihl(buf)
        if (ihl < Packets.IPV4_HEADER_LEN || ihl > n) return
        val totalLen = minOf(Packets.totalLength(buf), n)
        if (totalLen < ihl) return
        val srcIp = Packets.srcIp(buf)
        val dstIp = Packets.dstIp(buf)
        when (Packets.protocol(buf)) {
            Packets.PROTO_UDP -> handleUdp(buf, ihl, totalLen, srcIp, dstIp)
            Packets.PROTO_TCP -> handleTcp(buf, ihl, totalLen, srcIp, dstIp)
            else -> Unit // ICMP etc.: ignored (not routed to the internet, not logged)
        }
    }

    // ------------------------------------------------------------------
    // UDP
    // ------------------------------------------------------------------

    private fun handleUdp(buf: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + Packets.UDP_HEADER_LEN) return
        val srcPort = Packets.srcPort(buf, ihl)
        val dstPort = Packets.dstPort(buf, ihl)
        val udpLen = minOf(Packets.u16(buf, ihl + 4), totalLen - ihl)
        val payloadOff = ihl + Packets.UDP_HEADER_LEN
        val payloadLen = udpLen - Packets.UDP_HEADER_LEN
        if (payloadLen < 0) return

        val key = FlowKey(Packets.PROTO_UDP, srcIp, srcPort, dstIp, dstPort)
        val existing = tracker.get(key)
        if (existing != null) {
            existing.base.touch()
            existing.udp?.send(buf, payloadOff, payloadLen) // null relay == blocked tombstone
            return
        }

        // New flow.
        val base = FlowBase(key, System.currentTimeMillis())
        if (dstPort == UdpRelay.DNS_PORT && payloadLen > 0) {
            // DnsParser consumes from offset 0; DNS messages are small, copy once.
            DnsParser.parse(buf.copyOfRange(payloadOff, payloadOff + payloadLen))?.let { msg ->
                if (!msg.isResponse) base.dnsHostname = msg.questions.firstOrNull()
            }
        }
        val entry = FlowTracker.FlowEntry(base)
        tracker.put(entry)

        if (evaluator.evaluate(base)) {
            base.bytesSent.addAndGet(payloadLen.toLong())
            tracker.emitLog(base) // blocked flows are logged immediately
            return
        }

        val socket = try {
            DatagramSocket()
        } catch (t: Throwable) {
            tracker.emitLog(base)
            return
        }
        val remoteAddress = InetAddress.getByAddress(Packets.ipToBytes(dstIp))
        try {
            if (!protectUdp(socket)) throw IOException("VpnService.protect failed")
            socket.soTimeout = UdpRelay.SO_TIMEOUT_MS
            socket.connect(remoteAddress, dstPort)
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (ignored: Throwable) {
            }
            tracker.emitLog(base)
            return
        }
        val relay = UdpRelay(base, socket, remoteAddress, tunWriter, ipHostnameCache)
        entry.udp = relay
        relay.startReader()
        relay.send(buf, payloadOff, payloadLen)
    }

    // ------------------------------------------------------------------
    // TCP
    // ------------------------------------------------------------------

    private fun handleTcp(buf: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + Packets.TCP_HEADER_LEN) return
        val srcPort = Packets.srcPort(buf, ihl)
        val dstPort = Packets.dstPort(buf, ihl)
        val tcpHeaderLen = Packets.tcpHeaderLen(buf, ihl)
        if (tcpHeaderLen < Packets.TCP_HEADER_LEN || ihl + tcpHeaderLen > totalLen) return
        val flags = Packets.tcpFlags(buf, ihl)
        val seq = Packets.tcpSeq(buf, ihl)
        val payloadOff = ihl + tcpHeaderLen
        val payloadLen = totalLen - payloadOff

        val key = FlowKey(Packets.PROTO_TCP, srcIp, srcPort, dstIp, dstPort)
        val existing = tracker.get(key)
        if (existing == null) {
            if (flags and Packets.TCP_SYN != 0 && flags and Packets.TCP_ACK == 0) {
                val base = FlowBase(key, System.currentTimeMillis())
                val relay = TcpRelay(
                    base, tunWriter, evaluator, protectTcp, executor, tracker::onTcpClosed,
                )
                tracker.put(FlowTracker.FlowEntry(base, tcp = relay))
                relay.onSyn(seq)
            } else if (flags and Packets.TCP_RST == 0) {
                // Segment for a flow we don't know (e.g. after idle reaping): RST it.
                sendRstForUnknown(buf, ihl, srcIp, srcPort, dstIp, dstPort, seq, flags, payloadLen)
            }
            return
        }
        existing.tcp?.onTunPacket(flags, seq, buf, payloadOff, payloadLen)
    }

    /**
     * RFC 793 reset for a segment that matches no flow: RST with seq = their
     * ack when their ACK bit is set, else RST,ACK acking their sequence space.
     */
    private fun sendRstForUnknown(
        buf: ByteArray,
        ihl: Int,
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seq: Int,
        flags: Int,
        payloadLen: Int,
    ) {
        val out = ByteArray(Packets.IPV4_HEADER_LEN + Packets.TCP_HEADER_LEN)
        val len = if (flags and Packets.TCP_ACK != 0) {
            Packets.buildTcpPacket(
                out, dstIp, dstPort, srcIp, srcPort,
                seq = Packets.tcpAck(buf, ihl), ack = 0, flags = Packets.TCP_RST, window = 0,
            )
        } else {
            var consumed = payloadLen
            if (flags and Packets.TCP_SYN != 0) consumed += 1
            if (flags and Packets.TCP_FIN != 0) consumed += 1
            Packets.buildTcpPacket(
                out, dstIp, dstPort, srcIp, srcPort,
                seq = 0, ack = seq + consumed, flags = Packets.TCP_RST or Packets.TCP_ACK, window = 0,
            )
        }
        tunWriter.write(out, len)
    }

    companion object {
        const val READ_BUFFER_SIZE = 32 * 1024
    }
}
