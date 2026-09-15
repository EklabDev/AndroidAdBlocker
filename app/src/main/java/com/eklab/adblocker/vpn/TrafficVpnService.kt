package com.eklab.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.eklab.adblocker.R
import com.eklab.adblocker.core.RuleEngine
import com.eklab.adblocker.db.RetentionPolicy
import com.eklab.adblocker.repository.AppsRepository
import com.eklab.adblocker.repository.ConnectionsRepository
import com.eklab.adblocker.repository.RulesRepository
import com.eklab.adblocker.settings.SettingsRepository
import com.eklab.adblocker.vpn.tun.VpnPipeline
import com.eklab.adblocker.widget.AdBlockerWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject

/**
 * Local on-device firewall / traffic inspector. Establishes the TUN interface
 * and runs the [VpnPipeline] (packet loop, relays, rule checks, logging) for
 * the lifetime of the VPN session.
 *
 * IPv4-only by design (v1): the builder installs only an IPv4 address/route,
 * so IPv6 traffic is never routed into the TUN — it bypasses the VPN entirely
 * and is neither inspected nor logged. IPv6 support is future work.
 *
 * Lifecycle: [ACTION_START] goes foreground, applies the log-retention prune,
 * persists the "was running" flag for [BootReceiver] and starts the pipeline.
 * [ACTION_STOP], [onRevoke] (another VPN took over or the user disabled us in
 * system settings) and [onDestroy] all converge on the same teardown path.
 */
@AndroidEntryPoint
class TrafficVpnService : VpnService() {

    @Inject lateinit var ruleEngine: RuleEngine
    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var connectionsRepository: ConnectionsRepository
    @Inject lateinit var retentionPolicy: RetentionPolicy
    @Inject lateinit var appsRepository: AppsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunInterface: ParcelFileDescriptor? = null
    private var pipeline: VpnPipeline? = null

    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // VpnControl uses startForegroundService(), so we must go
                // foreground promptly even if already running.
                startForegroundWithNotification()
                if (!running) startVpn()
            }
            ACTION_STOP -> stopVpn()
            else -> Unit
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------

    private fun startVpn() {
        val tun = try {
            Builder()
                .setSession(SESSION_NAME)
                .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
                .addRoute(ROUTE_ADDRESS, ROUTE_PREFIX_LENGTH)
                .addDnsServer(DNS_SERVER)
                .setMtu(MTU)
                .apply {
                    try {
                        addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        // Should never happen (our own package); ignore.
                    }
                }
                .establish()
        } catch (t: Throwable) {
            null
        }
        if (tun == null) {
            // No consent (prepare() not granted) or TUN unavailable.
            stopSelf()
            return
        }

        tunInterface = tun
        running = true
        VpnControl.setRunning(true)
        VpnState.setWasRunning(this, true)
        AdBlockerWidgetProvider.updateAll(this)

        // Seed rules before any packet is evaluated. The repository's Flow
        // collector is async — without this sync load the engine can still be
        // empty when the first flows arrive (fail-open = rules "not applying").
        try {
            runBlocking { rulesRepository.refreshEngine() }
        } catch (t: Throwable) {
            // Fail open with whatever snapshot we have; hot-reload may catch up.
        }

        pipeline = VpnPipeline(
            context = this,
            service = this,
            ruleEngine = ruleEngine,
            settingsRepository = settingsRepository,
            connectionsRepository = connectionsRepository,
            appsRepository = appsRepository,
            scope = serviceScope,
        ).also { it.start(tun) }

        // Retention prune, off the packet path.
        serviceScope.launch {
            try {
                connectionsRepository.prune(retentionPolicy.cutoffMillis())
            } catch (t: Throwable) {
                // Best-effort housekeeping; RetentionWorker is the backstop.
            }
        }
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TrafficVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Inspecting local traffic")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    // ------------------------------------------------------------------
    // Stop
    // ------------------------------------------------------------------

    /** Full teardown. Idempotent; safe from onStartCommand/onRevoke/onDestroy. */
    private fun stopVpn() {
        if (!running && pipeline == null) return
        running = false

        // Closing the TUN fd unblocks the packet loop's read; the pipeline
        // then joins its threads and flushes pending logs synchronously.
        tunInterface?.let {
            try {
                it.close()
            } catch (e: IOException) {
                // already closed
            }
        }
        tunInterface = null
        pipeline?.stop()
        pipeline = null

        VpnControl.setRunning(false)
        VpnState.setWasRunning(this, false)
        AdBlockerWidgetProvider.updateAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Another VPN app took over or the user revoked us in system settings. */
    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.eklab.adblocker.vpn.START"
        const val ACTION_STOP = "com.eklab.adblocker.vpn.STOP"

        private const val NOTIFICATION_ID = 1
        private const val SESSION_NAME = "TrafficInspector"
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_PREFIX_LENGTH = 32
        private const val ROUTE_ADDRESS = "0.0.0.0"
        private const val ROUTE_PREFIX_LENGTH = 0
        private const val DNS_SERVER = "8.8.8.8"
        private const val MTU = 1500
    }
}
