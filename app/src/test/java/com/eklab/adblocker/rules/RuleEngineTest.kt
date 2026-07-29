package com.eklab.adblocker.rules

import com.eklab.adblocker.core.FlowContext
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.core.Verdict
import com.eklab.adblocker.db.entities.Rule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleEngineTest {

    private val engine = RuleEngineImpl()

    private fun rule(
        id: Long = 1,
        enabled: Boolean = true,
        priority: Int = 100,
        selectorType: SelectorType = SelectorType.HOST,
        selectorValue: String = "example.com",
        action: RuleAction = RuleAction.BLOCK,
    ) = Rule(
        id = id,
        name = "rule-$id",
        enabled = enabled,
        priority = priority,
        selectorType = selectorType,
        selectorValue = selectorValue,
        action = action,
    )

    private fun flow(
        appPackage: String? = "com.example.app",
        destIp: String = "1.2.3.4",
        protocol: ProtocolType = ProtocolType.HTTPS,
        sni: String? = null,
        dnsHostname: String? = null,
        resolvedHostname: String? = null,
    ) = FlowContext(
        appPackage = appPackage,
        uid = 10123,
        destIp = destIp,
        destPort = 443,
        protocol = protocol,
        sni = sni,
        dnsHostname = dnsHostname,
        resolvedHostname = resolvedHostname,
    )

    // ---------- APP ----------

    @Test
    fun app_exactMatch_blocks() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.APP, selectorValue = "com.example.app")))
        assertThat(engine.evaluate(flow(appPackage = "com.example.app")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun app_differentPackage_doesNotMatch() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.APP, selectorValue = "com.other.app")))
        assertThat(engine.evaluate(flow(appPackage = "com.example.app")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    // ---------- HOST ----------

    @Test
    fun host_exactMatch_blocks() {
        engine.updateRules(listOf(rule(selectorValue = "ads.example.com")))
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun host_matchIsCaseInsensitive() {
        engine.updateRules(listOf(rule(selectorValue = "ADS.Example.COM")))
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun host_trailingDotTolerated_onBothSides() {
        engine.updateRules(listOf(rule(selectorValue = "ads.example.com.")))
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))

        engine.updateRules(listOf(rule(selectorValue = "ads.example.com")))
        assertThat(engine.evaluate(flow(dnsHostname = "ads.example.com.")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun host_matchesAnyCandidate_sniDnsResolved() {
        engine.updateRules(listOf(rule(selectorValue = "ads.example.com")))

        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(dnsHostname = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(resolvedHostname = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(sni = "other.com", dnsHostname = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun host_noCandidate_noFieldsNull_doesNotThrow() {
        engine.updateRules(listOf(rule(selectorValue = "ads.example.com")))
        assertThat(engine.evaluate(flow()))
            .isEqualTo(Verdict.DefaultAllow)
    }

    // ---------- HOST_SUFFIX ----------

    @Test
    fun hostSuffix_matchesBaseDomain() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.HOST_SUFFIX, selectorValue = "x.com")))
        assertThat(engine.evaluate(flow(sni = "x.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun hostSuffix_matchesSubdomainAndDeepSubdomain() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.HOST_SUFFIX, selectorValue = "x.com")))
        assertThat(engine.evaluate(flow(sni = "ads.x.com")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(sni = "deep.ads.x.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun hostSuffix_selectorWithLeadingDotAlsoWorks() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.HOST_SUFFIX, selectorValue = ".x.com")))
        assertThat(engine.evaluate(flow(sni = "ads.x.com")))
            .isEqualTo(Verdict.Block(1))
    }

    @Test
    fun hostSuffix_negativeCases() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.HOST_SUFFIX, selectorValue = "x.com")))
        assertThat(engine.evaluate(flow(sni = "notx.com")))
            .isEqualTo(Verdict.DefaultAllow)
        assertThat(engine.evaluate(flow(sni = "x.com.evil.com")))
            .isEqualTo(Verdict.DefaultAllow)
        assertThat(engine.evaluate(flow(sni = "example.com")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    // ---------- IP ----------

    @Test
    fun ip_exactMatch_blocks() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.IP, selectorValue = "1.2.3.4")))
        assertThat(engine.evaluate(flow(destIp = "1.2.3.4")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(destIp = "1.2.3.5")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun ip_cidrMatchAndNonMatch() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.IP, selectorValue = "1.2.3.0/24")))
        assertThat(engine.evaluate(flow(destIp = "1.2.3.77")))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(destIp = "1.2.4.1")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun ip_malformedValues_neverMatchNeverThrow() {
        engine.updateRules(
            listOf(
                rule(id = 1, selectorType = SelectorType.IP, selectorValue = "1.2.3.0/33"),
                rule(id = 2, selectorType = SelectorType.IP, selectorValue = "1.2.3/24"),
                rule(id = 3, selectorType = SelectorType.IP, selectorValue = "not-an-ip"),
                rule(id = 4, selectorType = SelectorType.IP, selectorValue = "1.2.3.4/24/8"),
                rule(id = 5, selectorType = SelectorType.IP, selectorValue = "1.2.3.0/abc"),
            )
        )
        assertThat(engine.evaluate(flow(destIp = "1.2.3.4")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun ip_ipv6ExactMatch_caseInsensitive() {
        engine.updateRules(
            listOf(rule(selectorType = SelectorType.IP, selectorValue = "2001:DB8::1"))
        )
        assertThat(engine.evaluate(flow(destIp = "2001:db8::1")))
            .isEqualTo(Verdict.Block(1))
    }

    // ---------- TYPE ----------

    @Test
    fun type_matchIsCaseInsensitive() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.TYPE, selectorValue = "quic")))
        assertThat(engine.evaluate(flow(protocol = ProtocolType.QUIC)))
            .isEqualTo(Verdict.Block(1))
        assertThat(engine.evaluate(flow(protocol = ProtocolType.HTTPS)))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun type_unknownName_neverMatches() {
        engine.updateRules(listOf(rule(selectorType = SelectorType.TYPE, selectorValue = "GOPHER")))
        assertThat(engine.evaluate(flow(protocol = ProtocolType.HTTPS)))
            .isEqualTo(Verdict.DefaultAllow)
    }

    // ---------- Engine semantics ----------

    @Test
    fun evaluate_lowerPriorityValueWins_firstMatchWins() {
        engine.updateRules(
            listOf(
                rule(id = 1, priority = 50, selectorValue = "ads.example.com"),
                rule(id = 2, priority = 10, selectorType = SelectorType.HOST_SUFFIX, selectorValue = "example.com"),
            )
        )
        // Both match; rule 2 has the lower priority value so it is evaluated first.
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(2))
    }

    @Test
    fun evaluate_firstMatchWins_regardlessOfAction() {
        engine.updateRules(
            listOf(
                rule(id = 1, priority = 10, selectorValue = "ads.example.com", action = RuleAction.ALLOW),
                rule(id = 2, priority = 20, selectorType = SelectorType.HOST_SUFFIX, selectorValue = "example.com", action = RuleAction.BLOCK),
            )
        )
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Allow(1))
    }

    @Test
    fun evaluate_disabledRulesIgnored() {
        engine.updateRules(
            listOf(rule(id = 1, enabled = false, selectorValue = "ads.example.com"))
        )
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun evaluate_emptyRuleSet_defaultAllow() {
        engine.updateRules(emptyList())
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun evaluate_beforeAnyUpdate_defaultAllow() {
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.DefaultAllow)
    }

    @Test
    fun updateRules_hotReload_swapsBehavior() {
        engine.updateRules(listOf(rule(id = 1, selectorValue = "ads.example.com")))
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.Block(1))

        engine.updateRules(listOf(rule(id = 2, selectorValue = "tracker.example.com")))
        assertThat(engine.evaluate(flow(sni = "ads.example.com")))
            .isEqualTo(Verdict.DefaultAllow)
        assertThat(engine.evaluate(flow(sni = "tracker.example.com")))
            .isEqualTo(Verdict.Block(2))
    }

    // ---------- matches() ----------

    @Test
    fun matches_ignoresEnabledFlagAndAction() {
        val disabledAllowRule = rule(
            enabled = false,
            selectorValue = "ads.example.com",
            action = RuleAction.ALLOW,
        )
        assertThat(engine.matches(disabledAllowRule, flow(sni = "ads.example.com")))
            .isTrue()
        assertThat(engine.matches(disabledAllowRule, flow(sni = "other.com")))
            .isFalse()
    }

    @Test
    fun matches_worksWithoutUpdateRules() {
        assertThat(
            engine.matches(
                rule(selectorType = SelectorType.APP, selectorValue = "com.example.app"),
                flow(appPackage = "com.example.app"),
            )
        ).isTrue()
    }

    @Test
    fun matches_malformedSelector_neverThrows() {
        val badRule = rule(selectorType = SelectorType.IP, selectorValue = "///")
        assertThat(engine.matches(badRule, flow())).isFalse()
    }
}
