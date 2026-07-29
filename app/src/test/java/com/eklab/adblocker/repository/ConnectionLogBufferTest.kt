package com.eklab.adblocker.repository

import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.db.entities.ConnectionLog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.concurrent.thread

class ConnectionLogBufferTest {

    private fun log(seq: Long) = ConnectionLog(
        timestamp = seq,
        appPackage = "com.a",
        appName = null,
        uid = 10001,
        protocol = ProtocolType.OTHER_TCP,
        destIp = "10.0.0.1",
        destPort = 80,
    )

    @Test
    fun `offer does not flush until the batch size is reached`() = runTest {
        val flushed = mutableListOf<List<ConnectionLog>>()
        val buffer = ConnectionLogBuffer(
            flushSink = { flushed += it },
            batchSize = 3,
            flushIntervalMs = 60_000L,
            scope = this,
        )

        buffer.offer(log(1))
        buffer.offer(log(2))
        runCurrent()
        assertThat(flushed).isEmpty()
        assertThat(buffer.size).isEqualTo(2)

        buffer.offer(log(3))
        runCurrent()
        assertThat(flushed).hasSize(1)
        assertThat(flushed.single()).hasSize(3)
        assertThat(buffer.size).isEqualTo(0)
    }

    @Test
    fun `due reflects the flush interval`() {
        var now = 10_000L
        val buffer = ConnectionLogBuffer(
            flushSink = {},
            batchSize = 100,
            flushIntervalMs = 5_000L,
            nowMs = { now },
        )

        // Nothing pending -> never due.
        assertThat(buffer.due(now)).isFalse()

        buffer.offer(log(1))
        assertThat(buffer.due(now)).isFalse()
        assertThat(buffer.due(now + 4_999L)).isFalse()
        assertThat(buffer.due(now + 5_000L)).isTrue()
    }

    @Test
    fun `flush drains pending logs and is safe when empty`() = runTest {
        val flushed = mutableListOf<List<ConnectionLog>>()
        val buffer = ConnectionLogBuffer(flushSink = { flushed += it }, batchSize = 100)

        buffer.flush()
        assertThat(flushed).isEmpty()

        buffer.offer(log(1))
        buffer.offer(log(2))
        buffer.flush()

        assertThat(flushed).hasSize(1)
        assertThat(flushed.single().map { it.timestamp }).containsExactly(1L, 2L).inOrder()
        assertThat(buffer.size).isEqualTo(0)
        assertThat(buffer.due(Long.MAX_VALUE)).isFalse()
    }

    @Test
    fun `concurrent offers are not lost`() = runTest {
        val flushed = mutableListOf<ConnectionLog>()
        val buffer = ConnectionLogBuffer(
            flushSink = { flushed += it },
            batchSize = 10_000,
            scope = null,
        )

        val writers = (1..4).map { t ->
            thread(name = "writer-$t") {
                repeat(250) { buffer.offer(log(it.toLong())) }
            }
        }
        writers.forEach { it.join() }

        assertThat(buffer.size).isEqualTo(1_000)
        buffer.flush()
        assertThat(flushed).hasSize(1_000)
    }
}
