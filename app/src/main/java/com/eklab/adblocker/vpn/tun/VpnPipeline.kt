package com.eklab.adblocker.vpn.tun

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.repository.AppsRepository
import com.eklab.adblocker.repository.ConnectionLogBuffer
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.settings.SettingsRepository
import com.eklab.adblocker.vpn.parse.IpHostnameCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the whole packet-processing pipeline for one VPN session and the
 * threads that run it:
 *
 * ```
 *  TUN fd ──read──> tun-loop thread ──> FlowTracker ──> UdpRelay (1 reader thread/flow)
 *        <─write── TunWriter (locked) <──             └─> TcpRelay (executor: connect + reader)
 *  maintenance thread (1s): reaps idle flows, flushes the ConnectionLogBuffer when due
 * ```
 *
 * Threading model:
 * - "tun-loop": blocking TUN reads, header parse, flow dispatch (single thread).
 * - "tun-maintenance": 1-second tick for reaping and time-based log flushes.
 * - "tcp-worker-*" cached executor: TCP upstream connects and socket reader loops.
 * - "udp-rdr-*": one short-lived reader thread per UDP flow.
 * - [scope] coroutines: batched Room writes from the [ConnectionLogBuffer].
 *
 * The caller keeps ownership of [tunFd]; closing it unblocks the reader.
 * [stop] then joins the loop, tears down every flow and flushes pending logs
 * synchronously so nothing is lost on shutdown.
 */
class VpnPipeline(
    context: Context,
    private val service: VpnService,
    ruleEngine: RuleEngine,
    settingsRepository: SettingsRepository,
    connectionsRepository: ConnectionsRepository,
    appsRepository: AppsRepository,
    private val scope: CoroutineScope,
) {

    private val ipHostnameCache = IpHostnameCache()
    private val attribution = AppAttribution(context, appsRepository)
    private val evaluator = VerdictEvaluator(ruleEngine, settingsRepository, ipHostnameCache, attribution)
    private val logBuffer = ConnectionLogBuffer(flushSink = connectionsRepository::insertAll, scope = scope)
    private val tracker = FlowTracker(logBuffer)

    private val executor = Executors.newCachedThreadPool(object : ThreadFactory {
        private val counter = AtomicInteger()
        override fun newThread(r: Runnable): Thread =
            Thread(r, "tcp-worker-${counter.incrementAndGet()}").apply { isDaemon = true }
    })

    private var packetLoop: PacketLoop? = null
    private var maintenance: Thread? = null
    @Volatile private var running = false

    fun start(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        val tunWriter = TunWriter(FileOutputStream(tunFd.fileDescriptor))

        val loop = PacketLoop(
            input = input,
            tunWriter = tunWriter,
            tracker = tracker,
            evaluator = evaluator,
            ipHostnameCache = ipHostnameCache,
            protectUdp = { socket -> service.protect(socket) },
            protectTcp = { socket -> service.protect(socket) },
            executor = executor,
        )
        packetLoop = loop
        running = true
        loop.start()

        maintenance = Thread({ maintenanceLoop() }, "tun-maintenance").apply {
            isDaemon = true
            start()
        }
    }

    private fun maintenanceLoop() {
        while (running) {
            try {
                Thread.sleep(MAINTENANCE_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            try {
                tracker.reap(now)
            } catch (t: Throwable) {
                // keep the tick alive
            }
            if (logBuffer.due(now)) {
                scope.launch {
                    try {
                        logBuffer.flush()
                    } catch (t: Throwable) {
                        // DB hiccup — the batch is retried on the next flush cycle.
                    }
                }
            }
        }
    }

    /** Full teardown; safe to call once from the service's stop path. */
    fun stop() {
        running = false
        maintenance?.interrupt()
        packetLoop?.stopAndJoin()
        try {
            maintenance?.join(1_000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        tracker.closeAll()
        executor.shutdownNow()
        // Final synchronous flush so no completed flow is lost.
        runBlocking {
            try {
                logBuffer.flush()
            } catch (t: Throwable) {
                // Room unavailable during shutdown — nothing more we can do.
            }
        }
        packetLoop = null
        maintenance = null
    }

    private companion object {
        const val MAINTENANCE_INTERVAL_MS = 1_000L
    }
}
