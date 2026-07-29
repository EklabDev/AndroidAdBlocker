package com.eklab.adblocker.vpn.tun

/**
 * Low-level IPv4 / TCP / UDP packet helpers: header field accessors, checksum
 * computation (including TCP/UDP pseudo-headers) and reply-packet builders.
 *
 * Everything here operates on caller-supplied buffers with explicit offsets so
 * the hot path can reuse a single read buffer per thread without copying.
 * IPs are passed as host-order [Int]s where the most significant byte is the
 * first octet (e.g. 10.0.0.2 == 0x0A000002).
 *
 * Pure Kotlin/JVM — no Android dependencies.
 */
object Packets {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val IPV4_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8
    const val TCP_HEADER_LEN = 20

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    // ---------- field accessors (buf[off] = first byte of the IPv4 header) ----------

    fun ipVersion(buf: ByteArray, off: Int = 0): Int = (buf[off].toInt() ushr 4) and 0xF

    /** IPv4 header length in bytes. */
    fun ihl(buf: ByteArray, off: Int = 0): Int = (buf[off].toInt() and 0xF) * 4

    fun protocol(buf: ByteArray, off: Int = 0): Int = buf[off + 9].toInt() and 0xFF

    fun totalLength(buf: ByteArray, off: Int = 0): Int = u16(buf, off + 2)

    fun srcIp(buf: ByteArray, off: Int = 0): Int = u32(buf, off + 12)

    fun dstIp(buf: ByteArray, off: Int = 0): Int = u32(buf, off + 16)

    fun srcPort(buf: ByteArray, transportOff: Int): Int = u16(buf, transportOff)

    fun dstPort(buf: ByteArray, transportOff: Int): Int = u16(buf, transportOff + 2)

    fun tcpSeq(buf: ByteArray, tcpOff: Int): Int = u32(buf, tcpOff + 4)

    fun tcpAck(buf: ByteArray, tcpOff: Int): Int = u32(buf, tcpOff + 8)

    /** TCP header length in bytes (data offset field). */
    fun tcpHeaderLen(buf: ByteArray, tcpOff: Int): Int =
        ((buf[tcpOff + 12].toInt() and 0xFF) ushr 4) * 4

    fun tcpFlags(buf: ByteArray, tcpOff: Int): Int = buf[tcpOff + 13].toInt() and 0xFF

    fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun u32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte()
        buf[off + 1] = v.toByte()
    }

    fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun ipToBytes(ip: Int): ByteArray = byteArrayOf(
        (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte(),
    )

    // ---------- checksums ----------

    /** One's-complement checksum over [len] bytes starting at [off], seeded with [initial]. */
    private fun checksum(buf: ByteArray, off: Int, len: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    /** Checksum of the IPv4 header ([headerLen] bytes at [off], checksum field zeroed). */
    fun ipHeaderChecksum(buf: ByteArray, off: Int, headerLen: Int): Int =
        checksum(buf, off, headerLen)

    /**
     * TCP/UDP checksum including the IPv4 pseudo-header
     * (src IP, dst IP, zero, protocol, transport length).
     */
    fun transportChecksum(
        srcIp: Int,
        dstIp: Int,
        proto: Int,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        var pseudo = 0L
        pseudo += (srcIp ushr 16) and 0xFFFF
        pseudo += srcIp and 0xFFFF
        pseudo += (dstIp ushr 16) and 0xFFFF
        pseudo += dstIp and 0xFFFF
        pseudo += proto
        pseudo += len
        return checksum(buf, off, len, pseudo)
    }

    // ---------- packet builders ----------

    /** Writes a 20-byte IPv4 header (no options) at [off] and fills in its checksum. */
    private fun writeIpHeader(
        out: ByteArray,
        off: Int,
        proto: Int,
        srcIp: Int,
        dstIp: Int,
        totalLen: Int,
    ) {
        out[off] = 0x45 // version 4, IHL 5
        out[off + 1] = 0 // DSCP/ECN
        putU16(out, off + 2, totalLen)
        putU16(out, off + 4, 0) // identification
        putU16(out, off + 6, 0) // flags/fragment offset
        out[off + 8] = 64 // TTL
        out[off + 9] = proto.toByte()
        putU16(out, off + 10, 0) // header checksum placeholder
        putU32(out, off + 12, srcIp)
        putU32(out, off + 16, dstIp)
        putU16(out, off + 10, ipHeaderChecksum(out, off, IPV4_HEADER_LEN))
    }

    /**
     * Builds a complete IPv4+UDP packet at the start of [out] carrying
     * [payloadLen] bytes of payload from [payloadOff].
     * Returns the total packet length written.
     */
    fun buildUdpPacket(
        out: ByteArray,
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOff: Int,
        payloadLen: Int,
    ): Int {
        val udpLen = UDP_HEADER_LEN + payloadLen
        val total = IPV4_HEADER_LEN + udpLen
        writeIpHeader(out, 0, PROTO_UDP, srcIp, dstIp, total)
        val u = IPV4_HEADER_LEN
        putU16(out, u, srcPort)
        putU16(out, u + 2, dstPort)
        putU16(out, u + 4, udpLen)
        putU16(out, u + 6, 0) // checksum placeholder
        System.arraycopy(payload, payloadOff, out, u + UDP_HEADER_LEN, payloadLen)
        var csum = transportChecksum(srcIp, dstIp, PROTO_UDP, out, u, udpLen)
        if (csum == 0) csum = 0xFFFF // a computed zero checksum is transmitted as all-ones
        putU16(out, u + 6, csum)
        return total
    }

    /**
     * Builds a complete IPv4+TCP packet (20-byte TCP header, no options) at the
     * start of [out]. Returns the total packet length written.
     */
    fun buildTcpPacket(
        out: ByteArray,
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOff: Int = 0,
        payloadLen: Int = 0,
    ): Int {
        val total = IPV4_HEADER_LEN + TCP_HEADER_LEN + payloadLen
        writeIpHeader(out, 0, PROTO_TCP, srcIp, dstIp, total)
        val t = IPV4_HEADER_LEN
        putU16(out, t, srcPort)
        putU16(out, t + 2, dstPort)
        putU32(out, t + 4, seq)
        putU32(out, t + 8, ack)
        out[t + 12] = (5 shl 4).toByte() // data offset = 5 (20 bytes, no options)
        out[t + 13] = flags.toByte()
        putU16(out, t + 14, window)
        putU16(out, t + 16, 0) // checksum placeholder
        putU16(out, t + 18, 0) // urgent pointer
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, out, t + TCP_HEADER_LEN, payloadLen)
        }
        val csum = transportChecksum(srcIp, dstIp, PROTO_TCP, out, t, TCP_HEADER_LEN + payloadLen)
        putU16(out, t + 16, csum)
        return total
    }
}
