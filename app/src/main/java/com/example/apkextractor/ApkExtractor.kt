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
     *
     * [onDelta] is called with the number of bytes written each chunk, so the
     * caller can show a live progress percentage.
     */
    fun extract(
        context: Context,
        treeUri: Uri,
        app: AppInfo,
        onDelta: (Int) -> Unit = {},
    ): Result {
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Destination folder is not accessible")

            if (app.isSplit) {
                val name = safeName(app, "apks")
                val target = createFile(dir, name, "application/octet-stream")
                writeSplitBundle(context, app, target, onDelta)
                Result.Success(name)
            } else {
                val name = safeName(app, "apk")
                val target = createFile(dir, name, "application/vnd.android.package-archive")
                context.contentResolver.openOutputStream(target.uri)!!.use { out ->
                    FileInputStream(app.sourceApks.first()).use { input ->
                        copyStream(input, out, onDelta)
                    }
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

    private fun writeSplitBundle(
        context: Context,
        app: AppInfo,
        target: DocumentFile,
        onDelta: (Int) -> Unit,
    ) {
        context.contentResolver.openOutputStream(target.uri)!!.use { raw ->
            ZipOutputStream(raw).use { zip ->
                app.sourceApks.forEach { path ->
                    zip.putNextEntry(ZipEntry(File(path).name))
                    FileInputStream(path).use { copyStream(it, zip, onDelta) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Copy input -> out in 64 KB chunks, reporting each chunk's size via [onDelta]. */
    private fun copyStream(input: java.io.InputStream, out: OutputStream, onDelta: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        val buffered = BufferedInputStream(input)
        while (true) {
            val read = buffered.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            onDelta(read)
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
