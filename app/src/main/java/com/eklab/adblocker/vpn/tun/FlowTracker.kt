package com.eklab.adblocker.vpn.tun

import com.eklab.adblocker.repository.ConnectionLogBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of live flows, keyed by their 5-tuple. Also owns flow expiry and
 * log emission:
 *
 * - The packet loop inserts entries on first packet and looks them up per packet.
 * - The maintenance thread calls [reap] roughly every second: UDP flows idle
 *   longer than [UDP_IDLE_MS] and TCP flows idle longer than [TCP_IDLE_MS] (or
 *   already terminated) are closed and logged.
 * - [TcpRelay] reports its own termination via [onTcpClosed].
 * - [closeAll] drains everything at VPN shutdown.
 *
 * Every flow emits exactly one [com.eklab.adblocker.db.entities.ConnectionLog],
 * guarded by [FlowBase.logged] under a per-flow monitor. Blocked flows are
 * logged immediately at block time; their entry (with a `null` relay) lingers
 * as a tombstone so retransmits are dropped silently, and is reaped later
 * without a second log line.
 */
class FlowTracker(private val logBuffer: ConnectionLogBuffer) {

    class FlowEntry(
        val base: FlowBase,
        @Volatile var udp: UdpRelay? = null,
        @Volatile var tcp: TcpRelay? = null,
    )

    private val flows = ConcurrentHashMap<FlowKey, FlowEntry>()

    fun get(key: FlowKey): FlowEntry? = flows[key]

    fun put(entry: FlowEntry) {
        flows[entry.base.key] = entry
    }

    /** Called by a [TcpRelay] exactly once when it reaches its terminal state. */
    fun onTcpClosed(relay: TcpRelay) {
        val entry = flows[relay.base.key] ?: return
        if (flows.remove(relay.base.key, entry)) {
            emitLog(entry.base)
        }
    }

    /** Offers the flow's log to the buffer, at most once per flow. */
    fun emitLog(base: FlowBase) {
        synchronized(base) {
            if (base.logged) return
            base.logged = true
            logBuffer.offer(base.toLog(System.currentTimeMillis()))
        }
    }

    /** Closes expired flows and logs them. Called from the maintenance thread. */
    fun reap(nowMs: Long) {
        for ((key, entry) in flows) {
            val idleMs = nowMs - entry.base.lastActivityMs
            val udp = entry.udp
            val tcp = entry.tcp
            when {
                udp != null && idleMs > UDP_IDLE_MS -> {
                    if (flows.remove(key, entry)) {
                        udp.close()
                        emitLog(entry.base)
                    }
                }
                tcp != null && tcp.isTerminated -> {
                    if (flows.remove(key, entry)) emitLog(entry.base)
                }
                tcp != null && idleMs > TCP_IDLE_MS -> {
                    if (flows.remove(key, entry)) {
                        tcp.abort()
                        emitLog(entry.base)
                    }
                }
                // Tombstone of a blocked flow: already logged, just expire it.
                udp == null && tcp == null && idleMs > BLOCKED_IDLE_MS -> {
                    flows.remove(key, entry)
                }
            }
        }
    }

    /** Closes every relay and logs every unlogged flow. Called at shutdown. */
    fun closeAll() {
        for ((key, entry) in flows) {
            if (flows.remove(key, entry)) {
                entry.udp?.close()
                entry.tcp?.abort()
                emitLog(entry.base)
            }
        }
    }

    val size: Int get() = flows.size

    companion object {
        const val UDP_IDLE_MS = 60_000L
        const val TCP_IDLE_MS = 60_000L
        const val BLOCKED_IDLE_MS = 60_000L
    }
}
