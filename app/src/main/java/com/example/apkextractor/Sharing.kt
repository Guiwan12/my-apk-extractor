package com.example.apkextractor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sharing an APK goes through a FileProvider: the installed APK lives in a
 * system directory we can't hand to another app directly, so we copy the base
 * APK into our own cache and expose it with a content:// URI that carries a
 * temporary read grant.
 *
 * (Splits can't be installed from a single shared file, so we share the base
 * APK only — same limitation every share feature has.)
 */
object Sharing {

    private const val AUTHORITY = "com.example.apkextractor.fileprovider"

    /** Copy the base APK into cache/shared and return a shareable content URI. */
    fun prepareShareUri(context: Context, app: AppInfo): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val name = "${app.label}_${app.versionName}"
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifEmpty { app.packageName } + ".apk"

        val dest = File(dir, name)
        File(app.sourceApks.first()).inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return FileProvider.getUriForFile(context, AUTHORITY, dest)
    }

    fun buildSendIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
