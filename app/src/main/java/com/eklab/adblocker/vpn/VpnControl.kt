package com.eklab.adblocker.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val NOTIFICATION_CHANNEL_ID = "vpn_status"

/**
 * Thin control surface used by the UI layer so it never touches the service directly.
 * The [TrafficVpnService] implements the actual start/stop actions.
 */
object VpnControl {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** Called by the service when it enters/leaves the running state. */
    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    /**
     * Live in-memory running flag, falling back to the persisted "was running"
     * hint after process death (before the service republishes [setRunning]).
     */
    fun isProtectionOn(context: Context): Boolean =
        _isRunning.value || VpnState.wasRunning(context)

    /** Returns the system consent intent, or null if consent was already granted. */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Starts the VPN if system consent is already granted; otherwise invokes
     * [launchConsent] with the [VpnService.prepare] intent.
     */
    fun startOrRequestConsent(context: Context, launchConsent: (Intent) -> Unit) {
        val consent = prepareIntent(context)
        if (consent != null) {
            launchConsent(consent)
        } else {
            start(context)
        }
    }

    fun start(context: Context) {
        val intent = Intent(context, TrafficVpnService::class.java)
            .setAction(TrafficVpnService.ACTION_START)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, TrafficVpnService::class.java)
            .setAction(TrafficVpnService.ACTION_STOP)
        context.startService(intent)
    }
}
