package com.eklab.adblocker.repository

import com.eklab.adblocker.core.FlowContext
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.ConnectionLogDao
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.RuleDao
import com.eklab.adblocker.db.entities.ConnectionLog
import com.eklab.adblocker.db.entities.Rule
import com.eklab.adblocker.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RulesRepository @Inject constructor(
    private val ruleDao: RuleDao,
    private val connectionLogDao: ConnectionLogDao,
    private val ruleEngine: RuleEngine,
    private val retentionPolicy: RetentionPolicy,
    @AppScope appScope: CoroutineScope,
) {

    /** All rules ordered by priority; emits on every DB change. */
    val rules: Flow<List<Rule>> = ruleDao.observeAll()

    init {
        // Keep the compiled rule engine hot-reloaded with every DB change.
        rules
            .onEach { ruleEngine.updateRules(it) }
            .launchIn(appScope)
    }

    /**
     * Synchronously seed [ruleEngine] from Room. The Flow collector above is
     * async, so the VPN service must call this before starting the packet
     * pipeline — otherwise the first packets (or, if this repository was never
     * created, *all* packets) evaluate against an empty snapshot and fail open.
     */
    suspend fun refreshEngine() {
        ruleEngine.updateRules(ruleDao.getAll())
    }

    suspend fun add(rule: Rule): Long = ruleDao.insert(rule)

    suspend fun update(rule: Rule) = ruleDao.update(rule)

    suspend fun delete(rule: Rule) = ruleDao.delete(rule)

    suspend fun setEnabled(id: Long, enabled: Boolean) = ruleDao.setEnabled(id, enabled)

    suspend fun reorder(orderedIds: List<Long>) = ruleDao.reorder(orderedIds)

    /**
     * Counts how many connections from the last [RetentionPolicy.RETENTION_DAYS] days
     * would have matched a rule with the given selector. The rule action does not
     * affect matching, so a placeholder BLOCK action is used for the probe rule.
     */
    suspend fun previewMatchCount(selectorType: SelectorType, selectorValue: String): Int {
        val probe = Rule(
            id = -1,
            name = "preview",
            enabled = true,
            priority = 0,
            selectorType = selectorType,
            selectorValue = selectorValue,
            action = RuleAction.BLOCK,
        )
        val now = System.currentTimeMillis()
        val logs = connectionLogDao.logsInWindow(retentionPolicy.cutoffMillis(now), now)
        return logs.count { ruleEngine.matches(probe, it.toFlowContext()) }
    }

    private fun ConnectionLog.toFlowContext() = FlowContext(
        appPackage = appPackage,
        uid = uid,
        destIp = destIp,
        destPort = destPort,
        protocol = protocol,
        sni = sni,
        dnsHostname = dnsHostname,
        resolvedHostname = resolvedHostname,
    )
}
