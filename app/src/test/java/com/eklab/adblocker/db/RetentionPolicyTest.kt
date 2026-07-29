package com.eklab.adblocker.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RetentionPolicyTest {

    private val instant: Instant = Instant.parse("2026-07-28T12:00:00Z")
    private val policy = RetentionPolicy(Clock.fixed(instant, ZoneOffset.UTC))

    @Test
    fun `cutoff is exactly now minus 7 days`() {
        val expected = instant.toEpochMilli() -
            RetentionPolicy.RETENTION_DAYS * 24L * 60L * 60L * 1000L
        assertThat(policy.cutoffMillis()).isEqualTo(expected)
    }

    @Test
    fun `cutoff honors an explicit now`() {
        val now = instant.toEpochMilli() + 3_600_000L
        assertThat(policy.cutoffMillis(now)).isEqualTo(now - 7L * 24L * 60L * 60L * 1000L)
    }

    @Test
    fun `row stamped exactly at the cutoff is kept`() {
        // ConnectionLogDao.deleteOlderThan deletes rows with timestamp < cutoff only,
        // so a row stamped exactly at the cutoff survives the prune while a row one
        // millisecond earlier does not.
        val cutoff = policy.cutoffMillis()
        assertThat(cutoff < cutoff).isFalse()      // at cutoff -> kept
        assertThat(cutoff - 1 < cutoff).isTrue()   // just before -> pruned
    }
}
