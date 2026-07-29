package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.db.entities.ConnectionLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Identity of one flow as seen on the TUN device: the 5-tuple
 * (protocol, client IP/port, server IP/port). The client side is always the
 * TUN address (10.0.0.2).
 */
data class FlowKey(
    val protocol: Int,
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
)

/**
 * Mutable per-flow metadata and counters shared between the packet-loop
 * thread, relay threads and the maintenance reaper. Also carries everything
 * needed to emit the final [ConnectionLog].
 *
 * Writes to the `@Volatile` fields happen on whatever thread produced the
 * metadata (loop thread for client-side data, relay threads for server-side);
 * reads happen on the reaper / logging thread. Byte counters are atomic.
 */
class FlowBase(val key: FlowKey, val startMs: Long) {

    @Volatile var lastActivityMs: Long = startMs

    /** Payload bytes client -> server. */
    val bytesSent = AtomicLong(0)

    /** Payload bytes server -> client. */
    val bytesReceived = AtomicLong(0)

    @Volatile var sni: String? = null
    @Volatile var dnsHostname: String? = null
    @Volatile var resolvedHostname: String? = null
    @Volatile var httpHost: String? = null

    @Volatile var uid: Int = UNKNOWN_UID
    @Volatile var appPackage: String? = null
    @Volatile var appName: String? = null

    @Volatile var protocolType: ProtocolType =
        if (key.protocol == Packets.PROTO_UDP) ProtocolType.OTHER_UDP else ProtocolType.OTHER_TCP

    @Volatile var blocked: Boolean = false

    /** Rule that decided this flow; `null` for the built-in QUIC rule or default-allow. */
    @Volatile var matchedRuleId: Long? = null

    /** True once the flow's [ConnectionLog] has been offered to the buffer. */
    @Volatile var logged: Boolean = false

    fun touch(nowMs: Long = System.currentTimeMillis()) {
        lastActivityMs = nowMs
    }

    fun toLog(nowMs: Long): ConnectionLog = ConnectionLog(
        timestamp = startMs,
        appPackage = appPackage,
        appName = appName,
        uid = uid,
        protocol = protocolType,
        destIp = Packets.ipToString(key.dstIp),
        destPort = key.dstPort,
        sni = sni,
        dnsHostname = dnsHostname,
        resolvedHostname = resolvedHostname,
        bytesSent = bytesSent.get(),
        bytesReceived = bytesReceived.get(),
        blocked = blocked,
        matchedRuleId = matchedRuleId,
        durationMs = (nowMs - startMs).coerceAtLeast(0L),
    )

    companion object {
        /** Matches [android.os.Process.INVALID_UID]; kept as a constant so this class stays Android-free. */
        const val UNKNOWN_UID = -1
    }
}
