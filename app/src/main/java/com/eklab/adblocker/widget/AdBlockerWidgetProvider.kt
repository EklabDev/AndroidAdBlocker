package com.eklab.adblocker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eklab.adblocker.MainActivity
import com.eklab.adblocker.R
import com.eklab.adblocker.vpn.VpnControl

/**
 * Home-screen toggle for the local VPN / adblocker. State follows [VpnControl];
 * taps start or stop protection, opening [MainActivity] when VPN consent is missing.
 */
class AdBlockerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildRemoteViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            toggle(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.eklab.adblocker.widget.TOGGLE"
        const val EXTRA_START_VPN = "com.eklab.adblocker.extra.START_VPN"

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, AdBlockerWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            val views = buildRemoteViews(appContext)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        internal fun buildRemoteViews(context: Context): RemoteViews {
            val running = VpnControl.isProtectionOn(context)
            val views = RemoteViews(context.packageName, R.layout.adblocker_widget)
            views.setTextViewText(
                R.id.widget_status,
                context.getString(
                    if (running) R.string.widget_status_on else R.string.widget_status_off,
                ),
            )
            views.setTextViewText(
                R.id.widget_action,
                context.getString(
                    if (running) R.string.widget_turn_off else R.string.widget_turn_on,
                ),
            )
            val toggle = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AdBlockerWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, toggle)
            views.setOnClickPendingIntent(R.id.widget_action, toggle)
            return views
        }

        internal fun toggle(context: Context) {
            if (VpnControl.isProtectionOn(context)) {
                VpnControl.stop(context)
                return
            }
            val consent = VpnControl.prepareIntent(context)
            if (consent != null) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        )
                        .putExtra(EXTRA_START_VPN, true),
                )
            } else {
                VpnControl.start(context)
            }
        }
    }
}
