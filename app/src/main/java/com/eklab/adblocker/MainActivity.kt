package com.eklab.adblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.eklab.adblocker.ui.AppRoot
import com.eklab.adblocker.ui.theme.TrafficInspectorTheme
import com.eklab.adblocker.vpn.VpnControl
import com.eklab.adblocker.widget.AdBlockerWidgetProvider
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            VpnControl.start(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Granted or not, the VPN can run; the status notification is best-effort. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrafficInspectorTheme {
                AppRoot()
            }
        }
        maybeStartVpnFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeStartVpnFromIntent(intent)
    }

    /**
     * Widget taps that need VpnService consent land here with [AdBlockerWidgetProvider.EXTRA_START_VPN].
     */
    private fun maybeStartVpnFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AdBlockerWidgetProvider.EXTRA_START_VPN, false) != true) return
        intent.removeExtra(AdBlockerWidgetProvider.EXTRA_START_VPN)
        if (VpnControl.isRunning.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        VpnControl.startOrRequestConsent(this, vpnConsentLauncher::launch)
    }
}
