package com.eklab.adblocker.vpn.parse

import com.eklab.adblocker.core.ProtocolType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtocolClassifierTest {

    @Test
    fun sniWinsOverPortBasedGuesses() {
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 443, sni = "example.com"))
            .isEqualTo(ProtocolType.HTTPS)
        // SNI wins even on a non-443 port and over a Host header.
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 8443, sni = "example.com"))
            .isEqualTo(ProtocolType.HTTPS)
        assertThat(
            ProtocolClassifier.classify(
                isUdp = false, destPort = 80, sni = "example.com", httpHostHeader = "example.com",
            )
        ).isEqualTo(ProtocolType.HTTPS)
    }

    @Test
    fun udp443IsQuic() {
        assertThat(ProtocolClassifier.classify(isUdp = true, destPort = 443))
            .isEqualTo(ProtocolType.QUIC)
        // TCP/443 without SNI is not QUIC.
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 443))
            .isEqualTo(ProtocolType.OTHER_TCP)
    }

    @Test
    fun port53IsDnsOnBothTransports() {
        assertThat(ProtocolClassifier.classify(isUdp = true, destPort = 53))
            .isEqualTo(ProtocolType.DNS)
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 53))
            .isEqualTo(ProtocolType.DNS)
    }

    @Test
    fun port80OrHostHeaderIsHttp() {
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 80))
            .isEqualTo(ProtocolType.HTTP)
        // Host header on an odd port still classifies as HTTP.
        assertThat(
            ProtocolClassifier.classify(isUdp = false, destPort = 8080, httpHostHeader = "example.com")
        ).isEqualTo(ProtocolType.HTTP)
    }

    @Test
    fun fallbackToOther() {
        assertThat(ProtocolClassifier.classify(isUdp = false, destPort = 5228))
            .isEqualTo(ProtocolType.OTHER_TCP)
        assertThat(ProtocolClassifier.classify(isUdp = true, destPort = 123))
            .isEqualTo(ProtocolType.OTHER_UDP)
    }
}
