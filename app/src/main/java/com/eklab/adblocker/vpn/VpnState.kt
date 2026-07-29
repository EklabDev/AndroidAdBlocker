package com.eklab.adblocker.vpn

import android.content.Context
import androidx.core.content.edit

/**
 * Persists whether the VPN was running when the process went away, so
 * [BootReceiver] can restart it after a reboot (when VPN consent is still valid).
 */
internal object VpnState {

    private const val PREFS_NAME = "vpn_state"
    private const val KEY_WAS_RUNNING = "was_running"

    fun setWasRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_WAS_RUNNING, running) }
    }

    fun wasRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAS_RUNNING, false)
}
