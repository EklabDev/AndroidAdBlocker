package com.eklab.adblocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.eklab.adblocker.repository.RulesRepository
import com.eklab.adblocker.vpn.NOTIFICATION_CHANNEL_ID
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    /**
     * Eagerly create [RulesRepository] so its init block starts the rule-engine
     * hot-reload collector even when the UI never opens Rules/Apps/Connections
     * (VPN started from Home, boot restart, or START_STICKY process recovery).
     * Without this, Hilt leaves the engine on an empty snapshot and every flow
     * fails open until a rules-related ViewModel is finally constructed.
     */
    @Inject lateinit var rulesRepository: RulesRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification shown while the local VPN is active."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
