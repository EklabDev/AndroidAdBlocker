package com.eklab.adblocker.vpn.parse

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

class TlsSniParserTest {

    // --- ClientHello fixture builder -----------------------------------------

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun u24(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun sniExtension(hostname: String): ByteArray {
        val name = hostname.toByteArray(Charsets.US_ASCII)
        val data = ByteArrayOutputStream()
        u16(data, 3 + name.size) // server_name_list length
        data.write(0) // name_type: host_name
        u16(data, name.size)
        data.write(name)
        val ext = ByteArrayOutputStream()
        u16(ext, 0) // extension_type: server_name
        u16(ext, data.size())
        ext.write(data.toByteArray())
        return ext.toByteArray()
    }

    private fun supportedVersionsExtension(): ByteArray {
        val ext = ByteArrayOutputStream()
        u16(ext, 43) // supported_versions
        u16(ext, 3)
        ext.write(2) // list length
        u16(ext, 0x0304) // TLS 1.3
        return ext.toByteArray()
    }

    /** Builds a complete TLS record containing a ClientHello handshake message. */
    private fun buildClientHello(
        sni: String?,
        recordVersion: Int = 0x0301,
        legacyVersion: Int = 0x0303,
        sessionId: ByteArray = byteArrayOf(),
        withSupportedVersions: Boolean = false,
    ): ByteArray {
        val exts = ByteArrayOutputStream()
        if (withSupportedVersions) exts.write(supportedVersionsExtension())
        if (sni != null) exts.write(sniExtension(sni))

        val body = ByteArrayOutputStream()
        u16(body, legacyVersion)
        body.write(ByteArray(32) { it.toByte() }) // random
        body.write(sessionId.size)
        body.write(sessionId)
        u16(body, 4) // cipher_suites length
        u16(body, 0x1301)
        u16(body, 0xC02F)
        body.write(1) // compression_methods length
        body.write(0) // null compression
        u16(body, exts.size())
        body.write(exts.toByteArray())

        val hs = ByteArrayOutputStream()
        hs.write(1) // handshake type: ClientHello
        u24(hs, body.size())
        hs.write(body.toByteArray())

        val record = ByteArrayOutputStream()
        record.write(22) // content type: handshake
        u16(record, recordVersion)
        u16(record, hs.size())
        record.write(hs.toByteArray())
        return record.toByteArray()
    }

    /** Re-frames a ClientHello handshake message across several TLS records. */
    private fun splitIntoRecords(hello: ByteArray, chunkSize: Int): ByteArray {
        val hs = hello.copyOfRange(5, hello.size) // strip the original record header
        val out = ByteArrayOutputStream()
        var p = 0
        while (p < hs.size) {
            val n = minOf(chunkSize, hs.size - p)
            out.write(22)
            u16(out, 0x0301)
            u16(out, n)
            out.write(hs, p, n)
            p += n
        }
        return out.toByteArray()
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun sniIsExtractedFromTls12ClientHello() {
        val parser = TlsSniParser()
        val result = parser.feed(buildClientHello("example.com"))
        assertThat(result).isEqualTo("example.com")
        assertThat(parser.sni).isEqualTo("example.com")
        assertThat(parser.isFinished).isTrue()
        assertThat(parser.isFailed).isFalse()
    }

    @Test
    fun sniIsLowercased() {
        val parser = TlsSniParser()
        assertThat(parser.feed(buildClientHello("ExAmPle.COM"))).isEqualTo("example.com")
    }

    @Test
    fun fragmentationMidExtensionStillYieldsSni() {
        val hello = buildClientHello("example.com")
        // Split in the middle of the SNI hostname bytes.
        val haystack = String(hello, Charsets.US_ASCII)
        val splitAt = haystack.indexOf("example") + 3
        val parser = TlsSniParser()

        assertThat(parser.feed(hello.copyOf(splitAt))).isNull()
        assertThat(parser.isFinished).isFalse()
        assertThat(parser.feed(hello.copyOfRange(splitAt, hello.size))).isEqualTo("example.com")
        assertThat(parser.isFinished).isTrue()
    }

    @Test
    fun recordSplitAcrossThreeFeedsStillWorks() {
        val hello = buildClientHello("example.com")
        val parser = TlsSniParser()
        assertThat(parser.feed(hello.copyOf(2))).isNull() // mid record header
        assertThat(parser.feed(hello.copyOfRange(2, 10))).isNull() // mid handshake
        assertThat(parser.feed(hello.copyOfRange(10, hello.size))).isEqualTo("example.com")
    }

    @Test
    fun clientHelloSpanningTwoRecords() {
        val hello = buildClientHello("example.com")
        val handshakeLen = hello.size - 5
        val multi = splitIntoRecords(hello, chunkSize = handshakeLen / 2)
        assertThat(multi.size).isGreaterThan(hello.size) // sanity: extra record header added
        val parser = TlsSniParser()
        assertThat(parser.feed(multi)).isEqualTo("example.com")
        assertThat(parser.isFinished).isTrue()
    }

    @Test
    fun tls13StyleClientHello() {
        val parser = TlsSniParser()
        val hello = buildClientHello(
            sni = "example.com",
            recordVersion = 0x0301,
            legacyVersion = 0x0303,
            sessionId = ByteArray(32) { 0x11 },
            withSupportedVersions = true,
        )
        assertThat(parser.feed(hello)).isEqualTo("example.com")
    }

    @Test
    fun clientHelloWithoutSniFinishesWithNull() {
        val parser = TlsSniParser()
        assertThat(parser.feed(buildClientHello(sni = null))).isNull()
        assertThat(parser.isFinished).isTrue()
        assertThat(parser.isFailed).isFalse()
        assertThat(parser.sni).isNull()
    }

    @Test
    fun nonTlsBytesFailWithoutCrashing() {
        val parser = TlsSniParser()
        val http = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertThat(parser.feed(http)).isNull()
        assertThat(parser.isFailed).isTrue()
        assertThat(parser.isFinished).isFalse()
        // Feeding more after failure is a no-op.
        assertThat(parser.feed(buildClientHello("example.com"))).isNull()
    }

    @Test
    fun emptyAndTinyFeedsDoNotCrash() {
        val parser = TlsSniParser()
        assertThat(parser.feed(ByteArray(0))).isNull()
        assertThat(parser.feed(byteArrayOf(22))).isNull() // lone content-type byte
        assertThat(parser.isFailed).isFalse()
        assertThat(parser.isFinished).isFalse()
    }
}
