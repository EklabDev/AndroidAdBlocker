package com.eklab.adblocker.repository

import app.cash.turbine.test
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.ConnectionLogDao
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.db.RuleDao
import com.eklab.adblocker.db.entities.ConnectionLog
import com.eklab.adblocker.db.entities.Rule
import com.google.common.truth.Truth.assertThat
import io.mockk.any
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.match
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class RulesRepositoryTest {

    private lateinit var rulesFlow: MutableStateFlow<List<Rule>>
    private lateinit var ruleDao: RuleDao
    private lateinit var connectionLogDao: ConnectionLogDao
    private lateinit var ruleEngine: RuleEngine
    private lateinit var appScope: TestScope
    private lateinit var repository: RulesRepository

    private val rule1 = Rule(
        id = 1,
        name = "Block ads",
        selectorType = SelectorType.HOST_SUFFIX,
        selectorValue = ".ads.com",
        action = RuleAction.BLOCK,
        priority = 0,
    )
    private val rule2 = Rule(
        id = 2,
        name = "Allow app",
        selectorType = SelectorType.APP,
        selectorValue = "com.x",
        action = RuleAction.ALLOW,
        priority = 1,
    )

    @Before
    fun setUp() {
        rulesFlow = MutableStateFlow(emptyList())
        ruleDao = mockk(relaxed = true) {
            every { observeAll() } returns rulesFlow
        }
        connectionLogDao = mockk(relaxed = true)
        ruleEngine = mockk(relaxed = true)
        // Unconfined so the hot-reload collector started in init runs eagerly.
        appScope = TestScope(UnconfinedTestDispatcher())
        repository = RulesRepository(ruleDao, connectionLogDao, ruleEngine, RetentionPolicy(), appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `rules emits dao snapshots`() = runTest {
        repository.rules.test {
            assertThat(awaitItem()).isEmpty()
            rulesFlow.value = listOf(rule1, rule2)
            assertThat(awaitItem()).containsExactly(rule1, rule2).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `engine is hot-reloaded on every emission`() = runTest {
        // The initial emission already happened during setUp.
        verify { ruleEngine.updateRules(emptyList()) }

        rulesFlow.value = listOf(rule1)
        verify { ruleEngine.updateRules(listOf(rule1)) }

        rulesFlow.value = listOf(rule1, rule2)
        verify { ruleEngine.updateRules(listOf(rule1, rule2)) }
    }

    @Test
    fun `refreshEngine seeds the engine from a dao snapshot`() = runTest {
        coEvery { ruleDao.getAll() } returns listOf(rule1, rule2)

        repository.refreshEngine()

        verify { ruleEngine.updateRules(listOf(rule1, rule2)) }
        coVerify { ruleDao.getAll() }
    }

    @Test
    fun `mutations delegate to the dao`() = runTest {
        repository.add(rule1)
        repository.update(rule2)
        repository.delete(rule1)
        repository.setEnabled(1L, false)
        repository.reorder(listOf(2L, 1L))

        coVerify { ruleDao.insert(rule1) }
        coVerify { ruleDao.update(rule2) }
        coVerify { ruleDao.delete(rule1) }
        coVerify { ruleDao.setEnabled(1L, false) }
        coVerify { ruleDao.reorder(listOf(2L, 1L)) }
    }

    @Test
    fun `addAllSkippingDuplicates inserts new host suffixes only`() = runTest {
        coEvery { ruleDao.getAll() } returns listOf(rule1)
        val duplicateNoDot = rule1.copy(id = 0, selectorValue = "ads.com")
        val duplicateCase = rule1.copy(id = 0, selectorValue = ".ADS.COM")
        val fresh = rule1.copy(id = 0, name = ".vungle.com", selectorValue = ".vungle.com")
        val emptySuffix = rule1.copy(id = 0, name = "empty", selectorValue = ".")

        val inserted = repository.addAllSkippingDuplicates(
            listOf(duplicateNoDot, duplicateCase, fresh, emptySuffix, rule2),
        )

        assertThat(inserted).isEqualTo(2)
        coVerify {
            ruleDao.insertAll(
                match { rules ->
                    rules.size == 2 &&
                        rules[0].selectorValue == ".vungle.com" &&
                        rules[1] == rule2
                },
            )
        }
    }

    @Test
    fun `addAllSkippingDuplicates skips insert when every suffix already exists`() = runTest {
        coEvery { ruleDao.getAll() } returns listOf(rule1)

        val inserted = repository.addAllSkippingDuplicates(
            listOf(rule1.copy(id = 0, selectorValue = "ADS.com")),
        )

        assertThat(inserted).isEqualTo(0)
        coVerify(exactly = 0) { ruleDao.insertAll(any()) }
    }

    @Test
    fun `addAllSkippingDuplicates skips duplicate suffixes inside the same batch`() = runTest {
        coEvery { ruleDao.getAll() } returns emptyList()
        val first = rule1.copy(id = 0, selectorValue = ".vungle.com")
        val second = rule1.copy(id = 0, selectorValue = "vungle.com")

        val inserted = repository.addAllSkippingDuplicates(listOf(first, second))

        assertThat(inserted).isEqualTo(1)
        coVerify {
            ruleDao.insertAll(match { it.size == 1 && it[0].selectorValue == ".vungle.com" })
        }
    }

    @Test
    fun `previewMatchCount counts engine matches over logged connections`() = runTest {
        val logs = listOf(connectionLog(1), connectionLog(2), connectionLog(3))
        coEvery { connectionLogDao.logsInWindow(any(), any()) } returns logs
        every { ruleEngine.matches(any(), any()) } returnsMany listOf(true, false, true)

        val count = repository.previewMatchCount(SelectorType.HOST, "ads.example.com")

        assertThat(count).isEqualTo(2)
        coVerify { connectionLogDao.logsInWindow(any(), any()) }
    }

    private fun connectionLog(id: Long) = ConnectionLog(
        id = id,
        timestamp = 1_700_000_000_000L,
        appPackage = "com.a",
        appName = "App A",
        uid = 10001,
        protocol = ProtocolType.HTTPS,
        destIp = "93.184.216.34",
        destPort = 443,
        sni = "ads.example.com",
    )
}
