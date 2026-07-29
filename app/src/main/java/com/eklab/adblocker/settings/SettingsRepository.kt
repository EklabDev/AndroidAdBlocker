package com.eklab.adblocker.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide user settings backed by SharedPreferences.
 * The VPN pipeline reads [blockQuic] to synthesize a high-priority built-in rule.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _blockQuic = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_QUIC, false))

    /** When true, UDP/443 (QUIC) is blocked so apps fall back to inspectable TCP/TLS. */
    val blockQuic: StateFlow<Boolean> = _blockQuic

    fun setBlockQuic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_BLOCK_QUIC, enabled) }
        _blockQuic.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "traffic_inspector_settings"
        const val KEY_BLOCK_QUIC = "block_quic"
    }
}
