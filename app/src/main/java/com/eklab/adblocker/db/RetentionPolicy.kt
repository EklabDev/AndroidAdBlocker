package com.eklab.adblocker.db

import java.time.Clock

/**
 * Computes the 7-day retention cutoff. Pure and testable via an injected [Clock].
 */
class RetentionPolicy(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val retentionDays: Long = RETENTION_DAYS,
) {
    /** Rows with timestamp strictly older than this value must be pruned. */
    fun cutoffMillis(now: Long = clock.millis()): Long = now - retentionDays * MILLIS_PER_DAY

    companion object {
        const val RETENTION_DAYS: Long = 7
        private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
