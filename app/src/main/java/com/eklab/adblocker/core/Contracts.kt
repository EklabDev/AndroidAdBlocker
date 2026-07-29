package com.eklab.adblocker.core

/** Coarse traffic classification used by the rule engine and the connection log. */
enum class ProtocolType { HTTPS, HTTP, DNS, QUIC, OTHER_TCP, OTHER_UDP }

enum class SelectorType { APP, HOST, HOST_SUFFIX, IP, TYPE }

enum class RuleAction { BLOCK, ALLOW }

/**
 * Everything the rule engine needs to decide a flow's fate.
 * Pure data, no Android dependencies — safe for JVM unit tests.
 */
data class FlowContext(
    val appPackage: String?,
    val uid: Int,
    val destIp: String,
    val destPort: Int,
    val protocol: ProtocolType,
    val sni: String? = null,
    val dnsHostname: String? = null,
    val resolvedHostname: String? = null,
) {
    /** Hostname candidates in priority order: SNI -> DNS query -> IP->hostname cache. */
    val hostnameCandidates: List<String>
        get() = listOfNotNull(sni, dnsHostname, resolvedHostname)
}

/** Result of a rule evaluation. */
sealed interface Verdict {
    /** No rule matched; traffic is allowed (documented default). */
    data object DefaultAllow : Verdict
    data class Allow(val ruleId: Long) : Verdict
    data class Block(val ruleId: Long) : Verdict
}

/**
 * Implemented by the compiled rule engine in the rules/ package.
 * The VPN pipeline talks to this interface only.
 */
interface RuleEngine {
    fun evaluate(flow: FlowContext): Verdict

    /**
     * True when this single rule's selector matches the flow, regardless of other
     * rules, priorities, or the rule's own action. Used by the UI rule-preview
     * ("this rule would have matched N connections") and by [evaluate] internally.
     */
    fun matches(rule: com.eklab.adblocker.db.entities.Rule, flow: FlowContext): Boolean

    /** Hot-swap the compiled snapshot of enabled rules; takes effect immediately. */
    fun updateRules(rules: List<com.eklab.adblocker.db.entities.Rule>)
}
