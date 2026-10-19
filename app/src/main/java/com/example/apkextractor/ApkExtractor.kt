package com.example.apkextractor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copies an app's APK file(s) — and optionally its icon — into a user-chosen
 * folder via the Storage Access Framework (SAF).
 */
object ApkExtractor {

    sealed interface Result {
        data class Success(val fileName: String) : Result
        data class Failure(val app: AppInfo, val error: Throwable) : Result
    }

    /**
     * Extract an app's APK.
     * - Single-APK app  -> "Label_version.apk"
     * - Split app       -> "Label_version.apks" (zip of base + all splits)
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

    /** Save the app's launcher icon as a PNG ("Label.png") into the chosen folder. */
    fun extractIcon(context: Context, treeUri: Uri, app: AppInfo): Result {
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Destination folder is not accessible")
            val name = safeName(app, "png")
            val target = createFile(dir, name, "image/png")
            val bitmap: Bitmap = app.icon.toBitmap()
            context.contentResolver.openOutputStream(target.uri)!!.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Result.Success(name)
        } catch (e: Exception) {
            Result.Failure(app, e)
        }
    }

    private fun writeSplitBundle(context: Context, app: AppInfo, target: DocumentFile) {
        context.contentResolver.openOutputStream(target.uri)!!.use { raw ->
            ZipOutputStream(raw).use { zip ->
                app.sourceApks.forEach { path ->
                    zip.putNextEntry(ZipEntry(File(path).name))
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
