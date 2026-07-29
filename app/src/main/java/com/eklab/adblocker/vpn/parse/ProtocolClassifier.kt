package com.eklab.adblocker.vpn.parse

import com.eklab.adblocker.core.ProtocolType

/**
 * Coarse per-flow protocol classification from transport and parsed metadata.
 *
 * Precedence (first match wins):
 * 1. [sni] present -> [ProtocolType.HTTPS] (a TLS ClientHello with SNI wins
 *    over any port-based guess).
 * 2. UDP/443 -> [ProtocolType.QUIC] (checked before generic UDP handling).
 * 3. Port 53, TCP or UDP -> [ProtocolType.DNS].
 * 4. Port 80, or an HTTP Host header observed -> [ProtocolType.HTTP].
 * 5. Fallback: [ProtocolType.OTHER_TCP] / [ProtocolType.OTHER_UDP].
 */
object ProtocolClassifier {

    fun classify(
        isUdp: Boolean,
        destPort: Int,
        sni: String? = null,
        httpHostHeader: String? = null,
    ): ProtocolType = when {
        sni != null -> ProtocolType.HTTPS
        isUdp && destPort == 443 -> ProtocolType.QUIC
        destPort == 53 -> ProtocolType.DNS
        destPort == 80 || httpHostHeader != null -> ProtocolType.HTTP
        isUdp -> ProtocolType.OTHER_UDP
        else -> ProtocolType.OTHER_TCP
    }
}
