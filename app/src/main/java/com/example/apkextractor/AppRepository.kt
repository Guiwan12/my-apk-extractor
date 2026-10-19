package com.example.apkextractor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Reads the list of installed apps from PackageManager.
 *
 * This is the heart of the original app's MainActivity:
 *   getPackageManager().getInstalledApplications(GET_META_DATA)   // the list
 *   applicationInfo.publicSourceDir                               // the APK path
 *   applicationInfo.loadLabel() / loadIcon()                      // name + icon
 *   packageManager.getPackageInfo(pkg).versionName / versionCode  // version
 *   (applicationInfo.flags and FLAG_SYSTEM)                       // system vs user
 */
class AppRepository(context: Context) {

    private val pm: PackageManager = context.packageManager

    /**
     * Load every installed app. This can be a few hundred entries and calls
     * loadIcon/loadLabel for each, so always run it off the main thread
     * (the ViewModel does that on Dispatchers.IO).
     */
    fun loadInstalledApps(): List<AppInfo> {
        val flags = PackageManager.GET_META_DATA
        val apps: List<ApplicationInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(flags)
            }

        return apps.mapNotNull { appInfo ->
            try {
                toAppInfo(appInfo)
            } catch (e: Exception) {
                // Some entries (odd system packages) can throw; just skip them.
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun toAppInfo(appInfo: ApplicationInfo): AppInfo {
        val packageInfo: PackageInfo = getPackageInfoCompat(appInfo.packageName)

        val versionName = packageInfo.versionName ?: "?"
        val versionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

        // Base APK + any split APKs.
        val sources = buildList {
            appInfo.publicSourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.let { addAll(it) }
        }

        val totalSize = sources.sumOf { path ->
            runCatching { File(path).length() }.getOrDefault(0L)
        }

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        return AppInfo(
            label = appInfo.loadLabel(pm).toString(),
            packageName = appInfo.packageName,
            versionName = versionName,
            versionCode = versionCode,
            icon = appInfo.loadIcon(pm),
            isSystemApp = isSystem,
            sourceApks = sources,
            totalSizeBytes = totalSize,
        )
    }

    private fun getPackageInfoCompat(pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
}
