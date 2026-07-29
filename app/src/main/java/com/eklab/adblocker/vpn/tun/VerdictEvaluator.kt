package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.core.FlowContext
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.core.Verdict
import com.eklab.adblocker.settings.SettingsRepository
import com.eklab.adblocker.vpn.parse.IpHostnameCache
import com.eklab.adblocker.vpn.parse.ProtocolClassifier

/**
 * Single place where a flow's fate is decided. Called once per flow, as soon
 * as enough metadata is available (immediately for plain UDP / non-web TCP,
 * after SNI or Host extraction for TCP/443 and TCP/80).
 *
 * Fills the app-attribution, hostname, protocol-classification and verdict
 * fields on [FlowBase] and returns whether the flow must be blocked.
 *
 * The QUIC toggle is modelled as a built-in highest-priority rule: when
 * [SettingsRepository.blockQuic] is on and the flow classifies as QUIC, the
 * flow is blocked with `matchedRuleId = null` (no user rule was involved).
 */
class VerdictEvaluator(
    private val ruleEngine: RuleEngine,
    private val settingsRepository: SettingsRepository,
    private val ipHostnameCache: IpHostnameCache,
    private val attribution: AppAttribution,
) {

    /** Returns `true` when the flow must be blocked. Never throws. */
    fun evaluate(base: FlowBase): Boolean {
        val key = base.key
        try {
            base.uid = attribution.resolveUid(key.protocol, key.srcIp, key.srcPort, key.dstIp, key.dstPort)
            base.appPackage = attribution.packageForUid(base.uid)
            base.appName = base.appPackage?.let(attribution::labelForPackage)
            if (base.resolvedHostname == null) {
                base.resolvedHostname = ipHostnameCache.get(Packets.ipToString(key.dstIp))
            }
            base.protocolType = ProtocolClassifier.classify(
                isUdp = key.protocol == Packets.PROTO_UDP,
                destPort = key.dstPort,
                sni = base.sni,
                httpHostHeader = base.httpHost,
            )

            if (base.protocolType == ProtocolType.QUIC && settingsRepository.blockQuic.value) {
                // Built-in QUIC rule: force UDP/443 down so apps fall back to
                // inspectable TCP/TLS. Not a user rule -> matchedRuleId stays null.
                base.blocked = true
                base.matchedRuleId = null
                return true
            }

            val verdict = ruleEngine.evaluate(base.toFlowContext())
            when (verdict) {
                is Verdict.Block -> {
                    base.blocked = true
                    base.matchedRuleId = verdict.ruleId
                    return true
                }
                is Verdict.Allow -> {
                    base.blocked = false
                    base.matchedRuleId = verdict.ruleId
                }
                Verdict.DefaultAllow -> {
                    base.blocked = false
                    base.matchedRuleId = null
                }
            }
        } catch (t: Throwable) {
            // Fail open: a broken rule engine must not take down connectivity.
            base.blocked = false
        }
        return false
    }

    private fun FlowBase.toFlowContext() = FlowContext(
        appPackage = appPackage,
        uid = uid,
        destIp = Packets.ipToString(key.dstIp),
        destPort = key.dstPort,
        protocol = protocolType,
        sni = sni,
        dnsHostname = dnsHostname,
        resolvedHostname = resolvedHostname,
    )
}
