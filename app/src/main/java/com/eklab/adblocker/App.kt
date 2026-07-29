package com.eklab.adblocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.eklab.adblocker.vpn.NOTIFICATION_CHANNEL_ID
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {

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
