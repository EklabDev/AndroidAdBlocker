package com.eklab.adblocker.rules

import com.eklab.adblocker.core.FlowContext
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.core.Verdict
import com.eklab.adblocker.db.entities.Rule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compiled, thread-safe implementation of [RuleEngine].
 *
 * Concurrency model: [evaluate] runs on the VPN packet loop thread and must
 * never block. [updateRules] compiles the incoming list into an immutable
 * snapshot — enabled rules only, sorted by [Rule.priority] ascending — and
 * publishes it through a `@Volatile` field, so evaluation is lock-free.
 *
 * Semantics: rules in the snapshot are tried in priority order and the
 * **first matching rule wins**. A match with action [RuleAction.BLOCK] yields
 * [Verdict.Block]; [RuleAction.ALLOW] yields [Verdict.Allow]. An explicit
 * ALLOW rule wins only by priority ordering like any other rule — it does not
 * beat anything else. If no rule matches, [Verdict.DefaultAllow] is returned.
 *
 * Matching itself lives in [matchesSelector], an internal function taking
 * (Rule, FlowContext); this keeps the public API stable if compound selectors
 * (new fields on [Rule]) are added later.
 */
@Singleton
class RuleEngineImpl @Inject constructor() : RuleEngine {

    /** Immutable compiled snapshot; replaced atomically by [updateRules]. */
    @Volatile
    private var snapshot: List<Rule> = emptyList()

    override fun evaluate(flow: FlowContext): Verdict {
        for (rule in snapshot) {
            if (matchesSelector(rule, flow)) {
                return when (rule.action) {
                    RuleAction.BLOCK -> Verdict.Block(rule.id)
                    RuleAction.ALLOW -> Verdict.Allow(rule.id)
                }
            }
        }
        return Verdict.DefaultAllow
    }

    /**
     * True when [rule]'s selector matches [flow], ignoring the rule's
     * `enabled` flag, action, and priority. Never throws on malformed
     * selector values or null host fields.
     */
    override fun matches(rule: Rule, flow: FlowContext): Boolean = matchesSelector(rule, flow)

    override fun updateRules(rules: List<Rule>) {
        snapshot = rules
            .filter { it.enabled }
            .sortedBy { it.priority }
    }

    private fun matchesSelector(rule: Rule, flow: FlowContext): Boolean =
        when (rule.selectorType) {
            SelectorType.APP -> flow.appPackage == rule.selectorValue
            SelectorType.HOST ->
                flow.hostnameCandidates.any { normalizeHost(it) == normalizeHost(rule.selectorValue) }
            SelectorType.HOST_SUFFIX -> matchesHostSuffix(rule.selectorValue, flow.hostnameCandidates)
            SelectorType.IP -> matchesIp(rule.selectorValue, flow.destIp)
            SelectorType.TYPE -> matchesProtocolType(rule.selectorValue, flow.protocol)
        }

    /** Lowercases and strips a single trailing dot. */
    private fun normalizeHost(host: String): String =
        host.trimEnd('.').lowercase()

    private fun matchesHostSuffix(selectorValue: String, candidates: List<String>): Boolean {
        val base = normalizeHost(selectorValue).removePrefix(".")
        if (base.isEmpty()) return false
        return candidates.any { candidate ->
            val host = normalizeHost(candidate)
            host == base || host.endsWith(".$base")
        }
    }

    private fun matchesIp(selectorValue: String, destIp: String): Boolean {
        val selector = selectorValue.trim()
        val target = destIp.trim()
        if (selector.isEmpty() || target.isEmpty()) return false

        if (selector.contains('/')) {
            // IPv4 CIDR, e.g. "1.2.3.0/24". Malformed input never throws.
            val parts = selector.split('/')
            if (parts.size != 2) return false
            val network = parseIpv4(parts[0]) ?: return false
            val prefix = parts[1].toIntOrNull() ?: return false
            if (prefix !in 0..32) return false
            val targetInt = parseIpv4(target) ?: return false
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            return (network and mask) == (targetInt and mask)
        }

        // Exact address: string compare on a normalized form. IPv4 is compared
        // numerically so "1.2.3.4" variants ("01.2.3.4") also equal; IPv6 is
        // compared case-insensitively after stripping surrounding brackets.
        val selectorV4 = parseIpv4(selector)
        val targetV4 = parseIpv4(target)
        if (selectorV4 != null || targetV4 != null) {
            return selectorV4 != null && selectorV4 == targetV4
        }
        return normalizeIpv6(selector) == normalizeIpv6(target)
    }

    /** Parses dotted-quad IPv4 into its 32-bit int; null when malformed. */
    private fun parseIpv4(value: String): Int? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result.toInt()
    }

    private fun normalizeIpv6(value: String): String =
        value.removePrefix("[").removeSuffix("]").lowercase()

    private fun matchesProtocolType(selectorValue: String, protocol: ProtocolType): Boolean =
        protocol.name.equals(selectorValue.trim(), ignoreCase = true)
}
