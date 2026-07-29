package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.vpn.parse.DnsParser
import com.eklab.adblocker.vpn.parse.IpHostnameCache
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relays one allowed UDP flow: payloads arriving from the TUN are sent out a
 * [VpnService][android.net.VpnService]-protected [DatagramSocket] connected to
 * the real destination; a dedicated reader thread per flow receives responses
 * and writes IPv4+UDP reply packets back into the TUN via [TunWriter].
 *
 * For port-53 flows the reader additionally feeds DNS answers into
 * [IpHostnameCache] (responses are still relayed unchanged), and the packet
 * loop records the queried hostname on the flow.
 *
 * Threading: [send] is called from the packet-loop thread, [readerLoop] runs
 * on its own "udp-rdr-*" thread, [close] is called from the maintenance
 * reaper or shutdown path. Socket close unblocks the reader, which then exits.
 */
class UdpRelay(
    val base: FlowBase,
    private val socket: DatagramSocket,
    private val remoteAddress: InetAddress,
    private val tunWriter: TunWriter,
    private val ipHostnameCache: IpHostnameCache,
) {

    private val key = base.key
    private val closed = AtomicBoolean(false)

    fun startReader() {
        Thread({ readerLoop() }, "udp-rdr-${key.srcPort}->${key.dstPort}").apply {
            isDaemon = true
            start()
        }
    }

    /** Forwards one UDP payload from the TUN to the real destination. */
    fun send(buf: ByteArray, off: Int, len: Int) {
        if (closed.get() || len <= 0) return
        base.touch()
        base.bytesSent.addAndGet(len.toLong())
        try {
            socket.send(DatagramPacket(buf, off, len, remoteAddress, key.dstPort))
        } catch (e: IOException) {
            // Transient send failure — drop the datagram, UDP tolerates loss.
        }
    }

    private fun readerLoop() {
        val rbuf = ByteArray(MAX_DATAGRAM)
        val reply = ByteArray(MAX_DATAGRAM + Packets.IPV4_HEADER_LEN + Packets.UDP_HEADER_LEN)
        while (!closed.get()) {
            val n = try {
                val dp = DatagramPacket(rbuf, rbuf.size)
                socket.receive(dp)
                dp.length
            } catch (e: SocketTimeoutException) {
                continue // periodic wake-up so close() is noticed promptly
            } catch (e: SocketException) {
                break // socket closed by reap/shutdown
            } catch (e: IOException) {
                break
            }
            if (n <= 0) continue
            base.touch()
            base.bytesReceived.addAndGet(n.toLong())
            if (key.dstPort == DNS_PORT) {
                DnsParser.parse(rbuf, n)?.let { msg ->
                    if (msg.isResponse) {
                        for (answer in msg.answers) ipHostnameCache.put(answer.ip, answer.hostname)
                    }
                }
            }
            val packetLen = Packets.buildUdpPacket(
                reply, key.dstIp, key.dstPort, key.srcIp, key.srcPort, rbuf, 0, n,
            )
            tunWriter.write(reply, packetLen)
        }
    }

    /** Idempotent close: wakes the reader thread, which then exits. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    companion object {
        const val DNS_PORT = 53
        /** Receive buffer: comfortably above any MTU-sized datagram. */
        const val MAX_DATAGRAM = 4096
        const val SO_TIMEOUT_MS = 1000
    }
}
