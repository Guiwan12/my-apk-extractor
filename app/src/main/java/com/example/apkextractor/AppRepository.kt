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
 * Core calls:
 *   getInstalledApplications(GET_META_DATA)   // the list
 *   applicationInfo.publicSourceDir           // the APK path (+ splitSourceDirs)
 *   loadLabel() / loadIcon()                  // name + icon
 *   getPackageInfo(pkg, GET_PERMISSIONS)      // version, permissions, timestamps
 *   (flags and FLAG_SYSTEM)                   // system vs user
 */
class AppRepository(context: Context) {

    private val pm: PackageManager = context.packageManager

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
                null // skip odd packages that throw
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

        val sources = buildList {
            appInfo.publicSourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.let { addAll(it) }
        }
        val totalSize = sources.sumOf { path ->
            runCatching { File(path).length() }.getOrDefault(0L)
        }

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        // minSdkVersion is available from API 24; we target minSdk 26, so it's safe.
        val minSdk = appInfo.minSdkVersion

        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        return AppInfo(
            label = appInfo.loadLabel(pm).toString(),
            packageName = appInfo.packageName,
            versionName = versionName,
            versionCode = versionCode,
            icon = appInfo.loadIcon(pm),
            isSystemApp = isSystem,
            minSdk = minSdk,
            targetSdk = appInfo.targetSdkVersion,
            permissions = permissions,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            sourceApks = sources,
            totalSizeBytes = totalSize,
        )
    }

    private fun getPackageInfoCompat(pkg: String): PackageInfo {
        val flags = PackageManager.GET_PERMISSIONS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }
    }
}
