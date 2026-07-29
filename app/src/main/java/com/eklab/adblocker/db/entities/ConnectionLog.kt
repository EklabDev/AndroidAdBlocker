package com.eklab.adblocker.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eklab.adblocker.core.ProtocolType

@Entity(
    tableName = "connection_log",
    indices = [
        Index("timestamp"),
        Index("appPackage"),
        Index("blocked"),
        Index("resolvedHostname"),
    ],
)
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val appPackage: String?,
    val appName: String?,
    val uid: Int,
    val protocol: ProtocolType,
    val destIp: String,
    val destPort: Int,
    /** SNI extracted from the TLS ClientHello, if any. */
    val sni: String? = null,
    /** Hostname from an observed DNS query, if any. */
    val dnsHostname: String? = null,
    /** Hostname resolved via the IP->hostname cache, if any. */
    val resolvedHostname: String? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val blocked: Boolean = false,
    val matchedRuleId: Long? = null,
    val durationMs: Long = 0,
)
