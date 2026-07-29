package com.eklab.adblocker.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Restarts the VPN after a reboot when it was running before shutdown and the
 * user's VPN consent is still valid ([VpnService.prepare] returns null).
 *
 * All work here is fast (a SharedPreferences read plus an intent), so no
 * `goAsync()` is needed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VpnState.wasRunning(context)) return
        if (VpnService.prepare(context) != null) return // consent missing/revoked
        VpnControl.start(context)
    }
}
