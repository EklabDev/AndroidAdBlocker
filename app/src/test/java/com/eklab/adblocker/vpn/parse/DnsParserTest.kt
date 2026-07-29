package com.eklab.adblocker.vpn.parse

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Test

class DnsParserTest {

    // --- wire-format helpers -------------------------------------------------

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.removeSuffix(".").split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
    }

    private fun header(id: Int, flags: Int, qd: Int, an: Int): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, id)
        u16(out, flags)
        u16(out, qd)
        u16(out, an)
        u16(out, 0) // nscount
        u16(out, 0) // arcount
        return out.toByteArray()
    }

    private fun question(name: String, qtype: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        writeName(out, name)
        u16(out, qtype)
        u16(out, 1) // IN
        return out.toByteArray()
    }

    private fun queryPacket(id: Int = 0x1234, name: String = "example.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(id, 0x0100, qd = 1, an = 0))
        out.write(question(name))
        return out.toByteArray()
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun singleAQuery() {
        val msg = DnsParser.parse(queryPacket())!!
        assertThat(msg.transactionId).isEqualTo(0x1234)
        assertThat(msg.isResponse).isFalse()
        assertThat(msg.questions).containsExactly("example.com")
        assertThat(msg.answers).isEmpty()
    }

    @Test
    fun hostnameIsLowercasedAndTrailingDotStripped() {
        val msg = DnsParser.parse(queryPacket(name = "ExAmPle.COM."))!!
        assertThat(msg.questions).containsExactly("example.com")
    }

    @Test
    fun responseWithCompressedAanswer() {
        val out = ByteArrayOutputStream()
        out.write(header(0xbeef, 0x8180, qd = 1, an = 1))
        out.write(question("example.com"))
        // Answer: name = compression pointer to offset 12 (the question's QNAME).
        out.write(0xC0)
        out.write(0x0C)
        u16(out, 1) // TYPE A
        u16(out, 1) // CLASS IN
        out.write(byteArrayOf(0, 0, 0x0E, 0x10)) // TTL 3600
        u16(out, 4) // RDLENGTH
        out.write(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))

        val msg = DnsParser.parse(out.toByteArray())!!
        assertThat(msg.isResponse).isTrue()
        assertThat(msg.questions).containsExactly("example.com")
        assertThat(msg.answers).containsExactly(
            DnsParser.DnsAnswer("example.com", "93.184.216.34")
        )
    }

    @Test
    fun multipleQuestions() {
        val out = ByteArrayOutputStream()
        out.write(header(1, 0x0100, qd = 2, an = 0))
        out.write(question("example.com"))
        out.write(question("test.org", qtype = 28))
        val msg = DnsParser.parse(out.toByteArray())!!
        assertThat(msg.questions).containsExactly("example.com", "test.org").inOrder()
    }

    @Test
    fun aaaaAnswerIsCompressedIpv6() {
        val out = ByteArrayOutputStream()
        out.write(header(7, 0x8180, qd = 1, an = 1))
        out.write(question("example.com", qtype = 28))
        out.write(0xC0)
        out.write(0x0C)
        u16(out, 28) // TYPE AAAA
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 60)) // TTL
        u16(out, 16)
        // 2001:0db8:0000:0000:0000:0000:0000:0001
        out.write(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 1,
            )
        )
        val msg = DnsParser.parse(out.toByteArray())!!
        assertThat(msg.answers).containsExactly(
            DnsParser.DnsAnswer("example.com", "2001:db8::1")
        )
    }

    @Test
    fun nonAddressAnswersAreSkipped() {
        val out = ByteArrayOutputStream()
        out.write(header(9, 0x8180, qd = 1, an = 2))
        out.write(question("example.com"))
        // CNAME answer (skipped, but must be stepped over correctly).
        out.write(0xC0)
        out.write(0x0C)
        u16(out, 5) // TYPE CNAME
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 30))
        u16(out, 2)
        out.write(0xC0)
        out.write(0x0C)
        // A answer.
        out.write(0xC0)
        out.write(0x0C)
        u16(out, 1)
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 30))
        u16(out, 4)
        out.write(byteArrayOf(1, 2, 3, 4))

        val msg = DnsParser.parse(out.toByteArray())!!
        assertThat(msg.answers).containsExactly(DnsParser.DnsAnswer("example.com", "1.2.3.4"))
    }

    @Test
    fun truncatedPacketReturnsNull() {
        val full = queryPacket()
        assertThat(DnsParser.parse(full.copyOf(15))).isNull() // cut mid-question
        assertThat(DnsParser.parse(full.copyOf(5))).isNull() // cut mid-header
        assertThat(DnsParser.parse(ByteArray(0))).isNull()
        // length argument smaller than the array is honoured too.
        assertThat(DnsParser.parse(full, length = 14)).isNull()
    }

    @Test
    fun randomGarbageReturnsNullWithoutCrashing() {
        val rnd = Random(42)
        repeat(20) {
            val garbage = rnd.nextBytes(rnd.nextInt(1, 200))
            DnsParser.parse(garbage) // must not throw; result may be null
        }
        // Deliberately hostile header: huge counts, no body.
        assertThat(DnsParser.parse(header(1, 0x8180, qd = 0xFFFF, an = 0xFFFF))).isNull()
    }

    @Test
    fun compressionPointerLoopReturnsNull() {
        val out = ByteArrayOutputStream()
        out.write(header(1, 0x8180, qd = 0, an = 1))
        // Answer name at offset 12 is a pointer to itself.
        out.write(0xC0)
        out.write(0x0C)
        u16(out, 1)
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 30))
        u16(out, 4)
        out.write(byteArrayOf(1, 2, 3, 4))
        assertThat(DnsParser.parse(out.toByteArray())).isNull()
    }
}
