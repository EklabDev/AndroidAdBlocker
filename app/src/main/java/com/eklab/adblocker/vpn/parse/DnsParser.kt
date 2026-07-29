package com.eklab.adblocker.vpn.parse

/**
 * Parser for DNS wire-format messages (RFC 1035) carried as UDP payloads.
 *
 * Pure Kotlin/JVM — no Android dependencies, safe for unit tests. Never throws:
 * any truncated or malformed input returns `null`.
 */
object DnsParser {

    data class DnsAnswer(val hostname: String, val ip: String)

    data class DnsMessage(
        val transactionId: Int,
        val isResponse: Boolean,
        val questions: List<String>,
        val answers: List<DnsAnswer>,
    )

    private const val HEADER_LEN = 12
    private const val MAX_POINTER_JUMPS = 10
    private const val MAX_NAME_LEN = 253
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    /**
     * Parses [packet] (first [length] bytes) as a DNS message.
     * Returns `null` on any malformed or truncated input.
     */
    fun parse(packet: ByteArray, length: Int = packet.size): DnsMessage? {
        val len = minOf(length, packet.size)
        if (len < HEADER_LEN) return null

        val transactionId = u16(packet, 0)
        val flags = u16(packet, 2)
        val qdCount = u16(packet, 4)
        val anCount = u16(packet, 6)

        var pos = HEADER_LEN

        val questions = ArrayList<String>(qdCount)
        repeat(qdCount) {
            val (name, next) = readName(packet, len, pos) ?: return null
            if (next + 4 > len) return null // QTYPE + QCLASS
            questions += name
            pos = next + 4
        }

        val answers = ArrayList<DnsAnswer>()
        repeat(anCount) {
            val (name, next) = readName(packet, len, pos) ?: return null
            if (next + 10 > len) return null // TYPE, CLASS, TTL, RDLENGTH
            val type = u16(packet, next)
            val rdLength = u16(packet, next + 8)
            val rdata = next + 10
            if (rdata + rdLength > len) return null
            when {
                type == TYPE_A && rdLength == 4 ->
                    answers += DnsAnswer(name, ipv4ToString(packet, rdata))
                type == TYPE_AAAA && rdLength == 16 ->
                    answers += DnsAnswer(name, ipv6ToString(packet, rdata))
            }
            pos = rdata + rdLength
        }

        return DnsMessage(
            transactionId = transactionId,
            isResponse = flags and 0x8000 != 0,
            questions = questions,
            answers = answers,
        )
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    /**
     * Reads a possibly compressed domain name starting at [start].
     * Returns the lowercase dot-joined name (no trailing dot) and the offset
     * just past the name in the original stream, or `null` if malformed.
     */
    private fun readName(buf: ByteArray, limit: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var next = -1
        var jumps = 0
        while (true) {
            if (pos < 0 || pos >= limit) return null
            val b = buf[pos].toInt() and 0xFF
            when {
                b == 0 -> {
                    pos++
                    if (next == -1) next = pos
                    return sb.toString().lowercase() to next
                }
                b and 0xC0 == 0xC0 -> { // compression pointer
                    if (pos + 1 >= limit) return null
                    val ptr = ((b and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                    if (ptr >= limit) return null
                    if (next == -1) next = pos + 2
                    pos = ptr
                    if (++jumps > MAX_POINTER_JUMPS) return null
                }
                b and 0xC0 != 0 -> return null // 0x40/0x80 label types are reserved
                else -> {
                    if (pos + 1 + b > limit) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(buf, pos + 1, b, Charsets.US_ASCII))
                    if (sb.length > MAX_NAME_LEN) return null
                    pos += 1 + b
                }
            }
        }
    }

    private fun ipv4ToString(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}." +
            "${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    /** Formats 16 bytes as a compressed IPv6 string (RFC 5952 style, e.g. "2001:db8::1"). */
    private fun ipv6ToString(buf: ByteArray, off: Int): String {
        val groups = IntArray(8) { i ->
            ((buf[off + i * 2].toInt() and 0xFF) shl 8) or (buf[off + i * 2 + 1].toInt() and 0xFF)
        }
        // Longest run of zero groups (length >= 2) collapses to "::".
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) {
            bestStart = -1
            bestLen = 0
        }
        val sb = StringBuilder()
        var idx = 0
        while (idx < 8) {
            if (idx == bestStart) {
                sb.append("::")
                idx += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith("::")) sb.append(':')
            sb.append(Integer.toHexString(groups[idx]))
            idx++
        }
        if (sb.isEmpty()) sb.append("::")
        return sb.toString()
    }
}
