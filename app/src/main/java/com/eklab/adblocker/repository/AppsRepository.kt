package com.eklab.adblocker.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** An installed app as shown in the Apps screen and autocomplete pickers. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

@Singleton
class AppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cache: List<InstalledApp>? = null

    /**
     * All installed apps with launcher-resolved labels and icons, sorted by label.
     * The PackageManager query runs on the first call and the result is cached.
     */
    @Suppress("DEPRECATION")
    fun installedApps(): List<InstalledApp> {
        cache?.let { return it }
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
        cache = apps
        return apps
    }

    /** Display label for [packageName], or null when the app is not installed. */
    @Suppress("DEPRECATION")
    fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Launcher icon for [packageName], or null when the app is not installed. */
    fun appIcon(packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
