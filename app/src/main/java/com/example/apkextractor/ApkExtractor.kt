package com.example.apkextractor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copies an app's APK file(s) into a user-chosen folder using the Storage
 * Access Framework (SAF).
 *
 * This mirrors the original's extract step:
 *   - the user picks a destination folder via ACTION_OPEN_DOCUMENT_TREE
 *   - we persist that folder's URI permission
 *   - we stream-copy publicSourceDir -> a file in that folder
 *
 * SAF is the modern, permission-free way to write outside the app's private
 * storage on Android 10+ (no WRITE_EXTERNAL_STORAGE needed). The treeUri comes
 * from MainActivity's folder picker.
 */
object ApkExtractor {

    sealed interface Result {
        data class Success(val fileName: String) : Result
        data class Failure(val app: AppInfo, val error: Throwable) : Result
    }

    /**
     * Extract a single app.
     *
     * - A normal (single-APK) app is written as "Label_version.apk".
     * - A split app is bundled into one "Label_version.apks" zip containing the
     *   base + every split, which is how you'd reinstall it later. This is the
     *   improvement over the original, which only ever copied the base APK.
     */
    fun extract(context: Context, treeUri: Uri, app: AppInfo): Result {
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Destination folder is not accessible")

            if (app.isSplit) {
                val name = safeName(app, "apks")
                val target = createFile(dir, name, "application/octet-stream")
                writeSplitBundle(context, app, target)
                Result.Success(name)
            } else {
                val name = safeName(app, "apk")
                val target = createFile(dir, name, "application/vnd.android.package-archive")
                context.contentResolver.openOutputStream(target.uri)!!.use { out ->
                    copyFileTo(app.sourceApks.first(), out)
                }
                Result.Success(name)
            }
        } catch (e: Exception) {
            Result.Failure(app, e)
        }
    }

    private fun writeSplitBundle(context: Context, app: AppInfo, target: DocumentFile) {
        context.contentResolver.openOutputStream(target.uri)!!.use { raw ->
            ZipOutputStream(raw).use { zip ->
                app.sourceApks.forEach { path ->
                    val entryName = File(path).name // base.apk, split_config.xxhdpi.apk, ...
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(path).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun copyFileTo(sourcePath: String, out: OutputStream) {
        BufferedInputStream(FileInputStream(sourcePath)).use { input ->
            input.copyTo(out, bufferSize = 64 * 1024)
        }
    }

    /** If a file with this name already exists, DocumentFile appends " (1)" etc. */
    private fun createFile(dir: DocumentFile, name: String, mime: String): DocumentFile =
        dir.createFile(mime, name) ?: error("Could not create $name in destination")

    private fun safeName(app: AppInfo, ext: String): String {
        val base = "${app.label}_${app.versionName}"
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifEmpty { app.packageName }
        return "$base.$ext"
    }
}
