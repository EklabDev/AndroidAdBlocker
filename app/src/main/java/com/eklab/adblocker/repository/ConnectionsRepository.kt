package com.eklab.adblocker.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.eklab.adblocker.db.AppUsageSummary
import com.eklab.adblocker.db.ConnectionLogDao
import com.eklab.adblocker.db.HostSummary
import com.eklab.adblocker.db.entities.ConnectionLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of "today's" traffic stats for the dashboard. */
data class TodayStats(
    val connectionCount: Int,
    val blockedCount: Int,
    val totalBytes: Long,
)

@Singleton
class ConnectionsRepository @Inject constructor(
    private val dao: ConnectionLogDao,
) {

    /** Paged, newest-first connection feed. Null filters are passed through to the DAO. */
    fun pagedFeed(
        appPackage: String?,
        hostQuery: String?,
        blockedOnly: Boolean,
        startTime: Long,
        endTime: Long,
    ): Flow<PagingData<ConnectionLog>> =
        Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = {
                dao.pagedFeed(appPackage, hostQuery, blockedOnly, startTime, endTime)
            },
        ).flow

    fun appUsage(startTime: Long, endTime: Long): Flow<List<AppUsageSummary>> =
        dao.appUsageSummaries(startTime, endTime)

    fun topHosts(startTime: Long, endTime: Long): Flow<List<HostSummary>> =
        dao.hostSummaries(startTime, endTime)

    fun distinctHosts(since: Long): Flow<List<String>> =
        dao.distinctHosts(since)

    /** Combined count / blocked / bytes stats since [startOfDayMillis]. */
    fun todayStats(startOfDayMillis: Long): Flow<TodayStats> =
        combine(
            dao.countSince(startOfDayMillis),
            dao.blockedCountSince(startOfDayMillis),
            dao.bytesSince(startOfDayMillis),
        ) { count, blocked, bytes ->
            TodayStats(connectionCount = count, blockedCount = blocked, totalBytes = bytes)
        }

    /** Used as the flush sink for [ConnectionLogBuffer]. */
    suspend fun insertAll(logs: List<ConnectionLog>) = dao.insertAll(logs)

    /** Retention prune: removes rows strictly older than [cutoff]. Returns rows deleted. */
    suspend fun prune(cutoff: Long): Int = dao.deleteOlderThan(cutoff)
}
