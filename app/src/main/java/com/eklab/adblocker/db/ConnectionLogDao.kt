package com.eklab.adblocker.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eklab.adblocker.db.entities.ConnectionLog
import kotlinx.coroutines.flow.Flow

/** Per-app traffic totals over a time window, used by the per-app usage screen. */
data class AppUsageSummary(
    val appPackage: String?,
    val appName: String?,
    val totalBytes: Long,
    val connectionCount: Int,
    val blockedCount: Int,
    val distinctHosts: Int,
)

/** Per-host traffic totals over a time window, used by the top-hosts screen. */
data class HostSummary(
    val host: String,
    val totalBytes: Long,
    val connectionCount: Int,
    val blockedCount: Int,
)

@Dao
interface ConnectionLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ConnectionLog>)

    /**
     * Paged, newest-first feed. Null [appPackage]/[hostQuery] disable that filter;
     * [blockedOnly] = false disables the blocked filter. [hostQuery] is a substring
     * match against SNI, DNS hostname, resolved hostname and destination IP.
     */
    @Query(
        """
        SELECT * FROM connection_log
        WHERE (:appPackage IS NULL OR appPackage = :appPackage)
          AND (:hostQuery IS NULL
               OR sni LIKE '%' || :hostQuery || '%'
               OR dnsHostname LIKE '%' || :hostQuery || '%'
               OR resolvedHostname LIKE '%' || :hostQuery || '%'
               OR destIp LIKE '%' || :hostQuery || '%')
          AND (:blockedOnly = 0 OR blocked = 1)
          AND timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp DESC
        """
    )
    fun pagedFeed(
        appPackage: String?,
        hostQuery: String?,
        blockedOnly: Boolean,
        startTime: Long,
        endTime: Long,
    ): PagingSource<Int, ConnectionLog>

    /** Per-app aggregation over [startTime, endTime). Rows without a package form their own group. */
    @Query(
        """
        SELECT appPackage AS appPackage,
               MAX(appName) AS appName,
               SUM(bytesSent + bytesReceived) AS totalBytes,
               COUNT(*) AS connectionCount,
               SUM(CASE WHEN blocked = 1 THEN 1 ELSE 0 END) AS blockedCount,
               COUNT(DISTINCT COALESCE(sni, dnsHostname, resolvedHostname)) AS distinctHosts
        FROM connection_log
        WHERE timestamp >= :startTime AND timestamp < :endTime
        GROUP BY appPackage
        ORDER BY totalBytes DESC
        """
    )
    fun appUsageSummaries(startTime: Long, endTime: Long): Flow<List<AppUsageSummary>>

    /** Top 100 hosts over [startTime, endTime), falling back to destination IP when no hostname is known. */
    @Query(
        """
        SELECT COALESCE(sni, dnsHostname, resolvedHostname, destIp) AS host,
               SUM(bytesSent + bytesReceived) AS totalBytes,
               COUNT(*) AS connectionCount,
               SUM(CASE WHEN blocked = 1 THEN 1 ELSE 0 END) AS blockedCount
        FROM connection_log
        WHERE timestamp >= :startTime AND timestamp < :endTime
        GROUP BY host
        ORDER BY totalBytes DESC
        LIMIT 100
        """
    )
    fun hostSummaries(startTime: Long, endTime: Long): Flow<List<HostSummary>>

    /** Distinct observed hostnames since [since], for search autocomplete. */
    @Query(
        """
        SELECT DISTINCT COALESCE(sni, dnsHostname, resolvedHostname) AS host
        FROM connection_log
        WHERE timestamp >= :since
          AND COALESCE(sni, dnsHostname, resolvedHostname) IS NOT NULL
        ORDER BY host
        """
    )
    fun distinctHosts(since: Long): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM connection_log WHERE timestamp >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM connection_log WHERE timestamp >= :since AND blocked = 1")
    fun blockedCountSince(since: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(bytesSent + bytesReceived), 0) FROM connection_log WHERE timestamp >= :since")
    fun bytesSince(since: Long): Flow<Long>

    /**
     * Retention prune: deletes rows strictly older than [cutoff].
     * A row stamped exactly at the cutoff is kept. Returns the number of rows deleted.
     */
    @Query("DELETE FROM connection_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** All logs in [startTime, endTime), newest first (capped; used by the rule preview). */
    @Query(
        """
        SELECT * FROM connection_log
        WHERE timestamp >= :startTime AND timestamp < :endTime
        ORDER BY timestamp DESC
        LIMIT 20000
        """
    )
    suspend fun logsInWindow(startTime: Long, endTime: Long): List<ConnectionLog>
}
