package com.example.apkextractor

import android.graphics.drawable.Drawable

/**
 * One installed app, with everything we show in the list and everything we need
 * to extract it.
 *
 * This is the clean equivalent of the fields the original app stuffed into its
 * (obfuscated) model class: label, package name, version, size, the APK path(s),
 * and whether it's a system app.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: Drawable,
    val isSystemApp: Boolean,
    /**
     * Every APK file that makes up this app. Index 0 is the base APK
     * (publicSourceDir); any extra entries are split APKs (splitSourceDirs),
     * e.g. per-density or per-language splits. Modern apps are often splits —
     * the original only copied the base, which is the one real gap in it.
     */
    val sourceApks: List<String>,
    /** Total size in bytes across base + splits. */
    val totalSizeBytes: Long,
) {
    val isSplit: Boolean get() = sourceApks.size > 1

    /** "12.3 MB" style string for the UI. */
    val readableSize: String get() = formatBytes(totalSizeBytes)

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var i = 0
            while (value >= 1024 && i < units.lastIndex) {
                value /= 1024
                i++
            }
            return if (i == 0) "$bytes B" else String.format("%.1f %s", value, units[i])
        }
    }
}
