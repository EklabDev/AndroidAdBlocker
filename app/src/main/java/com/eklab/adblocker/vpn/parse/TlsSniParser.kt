package com.eklab.adblocker.vpn.parse

import java.io.ByteArrayOutputStream

/**
 * Stateful parser extracting the SNI hostname from a TLS ClientHello observed
 * on a TCP stream.
 *
 * The caller feeds the stream's bytes in order, starting from the first byte
 * (reassembly is done upstream), via [feed]; chunks may be split at arbitrary
 * boundaries. A ClientHello may span multiple TLS records and may share a
 * record with subsequent handshake messages.
 *
 * Usage: keep feeding until [isFinished] or [isFailed] becomes true.
 * - [isFinished]: the ClientHello was fully parsed; [sni] holds the lowercase
 *   hostname, or `null` when the ClientHello carried no SNI extension.
 * - [isFailed]: the stream is not TLS (e.g. plain HTTP) or is malformed;
 *   no hostname will ever be produced.
 *
 * Pure Kotlin/JVM, never throws.
 */
class TlsSniParser {

    var isFinished: Boolean = false
        private set
    var isFailed: Boolean = false
        private set
    var sni: String? = null
        private set

    private val recordBuf = ByteArrayOutputStream()
    private val handshakeBuf = ByteArrayOutputStream()

    /**
     * Feeds the next [length] bytes of the stream. Returns the SNI hostname
     * once complete, `null` while more data is needed or on failure.
     */
    fun feed(data: ByteArray, length: Int = data.size): String? {
        if (isFinished) return sni
        if (isFailed) return null
        val len = minOf(length, data.size).coerceAtLeast(0)
        recordBuf.write(data, 0, len)
        if (recordBuf.size() > MAX_BUFFER_BYTES) {
            isFailed = true
            return null
        }
        try {
            processRecords()
        } catch (e: MalformedException) {
            isFailed = true
        }
        return sni
    }

    /** Drains every complete TLS record into the handshake stream. */
    private fun processRecords() {
        while (!isFinished) {
            val buf = recordBuf.toByteArray()
            if (buf.size < RECORD_HEADER_LEN) return
            val contentType = buf[0].toInt() and 0xFF
            val majorVersion = buf[1].toInt() and 0xFF
            val recordLen = u16(buf, 3)
            // The first record of a TLS stream must be a handshake record with
            // a 3.x legacy version; anything else (e.g. "GET / HTTP/1.1") is
            // not TLS.
            if (contentType != CONTENT_TYPE_HANDSHAKE || majorVersion != 0x03) {
                throw MalformedException()
            }
            if (buf.size < RECORD_HEADER_LEN + recordLen) return
            handshakeBuf.write(buf, RECORD_HEADER_LEN, recordLen)
            val rest = buf.copyOfRange(RECORD_HEADER_LEN + recordLen, buf.size)
            recordBuf.reset()
            recordBuf.write(rest, 0, rest.size)
            if (handshakeBuf.size() > MAX_BUFFER_BYTES) throw MalformedException()
            processHandshake()
        }
    }

    /** Parses handshake messages; only the leading ClientHello is of interest. */
    private fun processHandshake() {
        val buf = handshakeBuf.toByteArray()
        if (buf.size < HANDSHAKE_HEADER_LEN) return
        val hsType = buf[0].toInt() and 0xFF
        val hsLen =
            ((buf[1].toInt() and 0xFF) shl 16) or
                ((buf[2].toInt() and 0xFF) shl 8) or
                (buf[3].toInt() and 0xFF)
        if (hsType != HANDSHAKE_TYPE_CLIENT_HELLO) throw MalformedException()
        if (buf.size < HANDSHAKE_HEADER_LEN + hsLen) return
        sni = parseClientHello(buf, HANDSHAKE_HEADER_LEN, hsLen)
        isFinished = true
    }

    /**
     * Parses a ClientHello body and returns the first DNS hostname from the
     * server_name extension, lowercased, or `null` when absent.
     */
    private fun parseClientHello(buf: ByteArray, off: Int, len: Int): String? {
        var p = off
        val end = off + len
        if (end > buf.size) throw MalformedException()

        p += 2 // legacy_version
        p += 32 // random
        p += 1 + lengthAt(buf, p, end, 1) // session_id
        p += 2 + lengthAt(buf, p, end, 2) // cipher_suites
        p += 1 + lengthAt(buf, p, end, 1) // compression_methods

        if (p == end) return null // no extensions block at all
        val extLen = lengthAt(buf, p, end, 2)
        p += 2
        if (p + extLen > end) throw MalformedException()
        val extEnd = p + extLen

        while (p + 4 <= extEnd) {
            val extType = u16(buf, p)
            val extDataLen = u16(buf, p + 2)
            p += 4
            if (p + extDataLen > extEnd) throw MalformedException()
            if (extType == EXT_SERVER_NAME) {
                return parseServerName(buf, p, extDataLen)
            }
            p += extDataLen
        }
        return null
    }

    /** Parses the server_name extension data; takes the first host_name (type 0) entry. */
    private fun parseServerName(buf: ByteArray, off: Int, len: Int): String? {
        val end = off + len
        if (len < 2) throw MalformedException()
        val listLen = u16(buf, off)
        var p = off + 2
        val listEnd = minOf(p + listLen, end)
        while (p + 3 <= listEnd) {
            val nameType = buf[p].toInt() and 0xFF
            val nameLen = u16(buf, p + 1)
            p += 3
            if (p + nameLen > listEnd) throw MalformedException()
            if (nameType == NAME_TYPE_HOST_NAME && nameLen > 0) {
                return String(buf, p, nameLen, Charsets.US_ASCII).lowercase()
            }
            p += nameLen
        }
        return null
    }

    /** Reads a length field of [size] bytes at [p] and bounds-checks the value. */
    private fun lengthAt(buf: ByteArray, p: Int, end: Int, size: Int): Int {
        if (p < 0 || p + size > end) throw MalformedException()
        val value = when (size) {
            1 -> buf[p].toInt() and 0xFF
            else -> u16(buf, p)
        }
        if (p + size + value > end) throw MalformedException()
        return value
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private class MalformedException : Exception()

    private companion object {
        const val RECORD_HEADER_LEN = 5
        const val HANDSHAKE_HEADER_LEN = 4
        const val CONTENT_TYPE_HANDSHAKE = 22
        const val HANDSHAKE_TYPE_CLIENT_HELLO = 1
        const val EXT_SERVER_NAME = 0
        const val NAME_TYPE_HOST_NAME = 0
        /** A TLS record is at most ~16 KB; anything beyond means garbage input. */
        const val MAX_BUFFER_BYTES = 64 * 1024
    }
}
