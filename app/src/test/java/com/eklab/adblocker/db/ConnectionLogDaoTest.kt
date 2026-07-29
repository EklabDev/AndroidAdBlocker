package com.eklab.adblocker.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.db.entities.ConnectionLog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ConnectionLogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.connectionLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun log(
        timestamp: Long,
        appPackage: String? = "com.a",
        appName: String? = "App A",
        destIp: String = "93.184.216.34",
        destPort: Int = 443,
        protocol: ProtocolType = ProtocolType.HTTPS,
        sni: String? = null,
        dnsHostname: String? = null,
        resolvedHostname: String? = null,
        bytesSent: Long = 10,
        bytesReceived: Long = 90,
        blocked: Boolean = false,
    ) = ConnectionLog(
        timestamp = timestamp,
        appPackage = appPackage,
        appName = appName,
        uid = 10001,
        protocol = protocol,
        destIp = destIp,
        destPort = destPort,
        sni = sni,
        dnsHostname = dnsHostname,
        resolvedHostname = resolvedHostname,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        blocked = blocked,
    )

    /** Seeds 4 rows (100 bytes each): com.a x2, com.b x2, blocked x2, 3 distinct hosts. */
    private suspend fun seed() {
        dao.insertAll(
            listOf(
                log(timestamp = BASE - 1000, appPackage = "com.a", sni = "api.example.com", blocked = true),
                log(timestamp = BASE, appPackage = "com.a", dnsHostname = "cdn.example.com"),
                log(timestamp = BASE + 1000, appPackage = "com.b", appName = "App B", resolvedHostname = "tracker.ads.com", blocked = true),
                log(timestamp = BASE + 2000, appPackage = "com.b", appName = "App B", destIp = "9.9.9.9"),
            ),
        )
    }

    private suspend fun loadAll(source: PagingSource<Int, ConnectionLog>): List<ConnectionLog> {
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false),
        )
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun `deleteOlderThan prunes only rows strictly older than the cutoff`() = runBlocking {
        seed()
        val deleted = dao.deleteOlderThan(BASE)
        assertThat(deleted).isEqualTo(1)

        val remaining = dao.logsInWindow(Long.MIN_VALUE, Long.MAX_VALUE)
        assertThat(remaining).hasSize(3)
        // Boundary semantics: the row stamped exactly at the cutoff is kept.
        assertThat(remaining.map { it.timestamp }).contains(BASE)
        assertThat(remaining.map { it.timestamp }).doesNotContain(BASE - 1000)
    }

    @Test
    fun `pagedFeed blockedOnly returns only blocked rows newest first`() = runBlocking {
        seed()
        val page = loadAll(dao.pagedFeed(null, null, true, Long.MIN_VALUE, Long.MAX_VALUE))
        assertThat(page).hasSize(2)
        assertThat(page.map { it.timestamp }).containsExactly(BASE + 1000, BASE - 1000).inOrder()
    }

    @Test
    fun `pagedFeed filters by app package`(): Unit = runBlocking {
        seed()
        val page = loadAll(dao.pagedFeed("com.a", null, false, Long.MIN_VALUE, Long.MAX_VALUE))
        assertThat(page).hasSize(2)
        assertThat(page.map { it.appPackage }.distinct()).containsExactly("com.a")
    }

    @Test
    fun `pagedFeed host query matches sni dns resolved hostname and ip`() = runBlocking {
        seed()
        assertThat(loadAll(dao.pagedFeed(null, "example", false, Long.MIN_VALUE, Long.MAX_VALUE))).hasSize(2)
        assertThat(loadAll(dao.pagedFeed(null, "tracker", false, Long.MIN_VALUE, Long.MAX_VALUE))).hasSize(1)
        assertThat(loadAll(dao.pagedFeed(null, "9.9.9", false, Long.MIN_VALUE, Long.MAX_VALUE))).hasSize(1)
    }

    @Test
    fun `pagedFeed respects the time window`() = runBlocking {
        seed()
        val page = loadAll(dao.pagedFeed(null, null, false, BASE, BASE + 1000))
        assertThat(page.map { it.timestamp }).containsExactly(BASE + 1000, BASE).inOrder()
    }

    @Test
    fun `distinctHosts returns distinct non-null coalesced hosts`(): Unit = runBlocking {
        seed()
        val hosts = dao.distinctHosts(0).first()
        assertThat(hosts).containsExactly("api.example.com", "cdn.example.com", "tracker.ads.com")
    }

    @Test
    fun `appUsageSummaries aggregates per app`() = runBlocking {
        seed()
        val summaries = dao.appUsageSummaries(0, Long.MAX_VALUE).first()
        assertThat(summaries).hasSize(2)
        val byPackage = summaries.associateBy { it.appPackage }

        val a = byPackage.getValue("com.a")
        assertThat(a.totalBytes).isEqualTo(200)
        assertThat(a.connectionCount).isEqualTo(2)
        assertThat(a.blockedCount).isEqualTo(1)
        assertThat(a.distinctHosts).isEqualTo(2)

        val b = byPackage.getValue("com.b")
        assertThat(b.totalBytes).isEqualTo(200)
        assertThat(b.connectionCount).isEqualTo(2)
        assertThat(b.blockedCount).isEqualTo(1)
        assertThat(b.distinctHosts).isEqualTo(1)
    }

    @Test
    fun `hostSummaries groups by coalesced host with ip fallback`() = runBlocking {
        seed()
        val summaries = dao.hostSummaries(0, Long.MAX_VALUE).first()
        assertThat(summaries).hasSize(4)

        val tracker = summaries.first { it.host == "tracker.ads.com" }
        assertThat(tracker.totalBytes).isEqualTo(100)
        assertThat(tracker.connectionCount).isEqualTo(1)
        assertThat(tracker.blockedCount).isEqualTo(1)

        // The row without any hostname falls back to its destination IP.
        assertThat(summaries.map { it.host }).contains("9.9.9.9")
    }

    @Test
    fun `since counters report totals`() = runBlocking {
        seed()
        assertThat(dao.countSince(0).first()).isEqualTo(4)
        assertThat(dao.blockedCountSince(0).first()).isEqualTo(2)
        assertThat(dao.bytesSince(0).first()).isEqualTo(400)
    }

    companion object {
        private const val BASE = 1_700_000_000_000L
    }
}
