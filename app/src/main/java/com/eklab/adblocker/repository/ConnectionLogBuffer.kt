package com.eklab.adblocker.repository

import com.eklab.adblocker.db.entities.ConnectionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Batches connection-log writes so the VPN packet path does not hit Room once per flow.
 *
 * Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 *
 * - [offer] is thread-safe and non-blocking; it is called from the packet thread.
 * - When [batchSize] logs accumulate, a flush is launched automatically on [scope]
 *   (when a scope was provided; otherwise the caller must call [flush] itself).
 * - The caller's timer loop should poll [due] and call [flush] when it returns true,
 *   so a partially-filled batch is never held longer than [flushIntervalMs].
 */
class ConnectionLogBuffer(
    private val flushSink: suspend (List<ConnectionLog>) -> Unit,
    private val batchSize: Int = 50,
    private val flushIntervalMs: Long = 5_000L,
    private val scope: CoroutineScope? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val pending = ArrayList<ConnectionLog>(batchSize)
    private var firstPendingAtMs: Long = -1L

    /** Number of logs currently waiting to be written. */
    val size: Int
        get() = synchronized(lock) { pending.size }

    fun offer(log: ConnectionLog) {
        val full = synchronized(lock) {
            if (pending.isEmpty()) {
                firstPendingAtMs = nowMs()
            }
            pending.add(log)
            pending.size >= batchSize
        }
        if (full) {
            scope?.launch { flush() }
        }
    }

    /**
     * True when there are pending logs that have been waiting at least
     * [flushIntervalMs], i.e. a time-based flush should happen now.
     */
    fun due(now: Long): Boolean = synchronized(lock) {
        pending.isNotEmpty() && firstPendingAtMs >= 0L && now - firstPendingAtMs >= flushIntervalMs
    }

    /** Writes all pending logs through [flushSink]. Safe to call when empty (a no-op). */
    suspend fun flush() {
        val batch: List<ConnectionLog> = synchronized(lock) {
            if (pending.isEmpty()) return
            val drained = pending.toList()
            pending.clear()
            firstPendingAtMs = -1L
            drained
        }
        flushSink(batch)
    }
}
