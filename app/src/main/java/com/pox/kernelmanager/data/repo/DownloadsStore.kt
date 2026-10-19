package com.pox.kernelmanager.data.repo

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pox.kernelmanager.PoxApplication
import java.io.File

/**
 * Writes into the public Downloads collection.
 *
 * A direct `File("/sdcard/Download/x.txt").writeText(...)` cannot work on API 29+
 * under scoped storage: the app holds no write permission on that path and only
 * fails once the write is attempted. MediaStore inserts a pending entry, streams
 * the bytes, then clears the pending flag to publish the file.
 */
object DownloadsStore {

    /**
     * Save [content] as [fileName] in Downloads and return a user-facing location.
     *
     * Throws if the write fails, so callers can report the real reason instead of
     * claiming a report was saved.
     */
    fun write(fileName: String, mimeType: String, content: ByteArray): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(fileName, mimeType, content)
            // The MediaStore content uri is opaque; show the user the real path.
            "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, fileName).also { it.writeBytes(content) }.absolutePath
        }

    /**
     * Stream [source] to `Downloads/[subDir]/[fileName]` and return the
     * user-facing location.
     *
     * The kernel flasher moves 64 MB partition images, so the whole-file
     * [write] overload would need the entire image in heap; this copies in
     * chunks instead. Throws on failure like [write], so a partial publish can
     * be reported (and cleaned up) instead of looking successful.
     */
    fun writeStream(subDir: String?, fileName: String, mimeType: String, source: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeStreamViaMediaStore(subDir, fileName, mimeType, source)
        } else {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = subDir?.takeIf { it.isNotBlank() }?.let { File(root, it) } ?: root
            dir.mkdirs()
            source.inputStream().buffered().use { input ->
                File(dir, fileName).outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        return listOfNotNull(
            Environment.DIRECTORY_DOWNLOADS,
            subDir?.takeIf { it.isNotBlank() },
            fileName
        ).joinToString("/")
    }

    /**
     * Deletes everything under `Downloads/[subDir]` (one backup set folder).
     *
     * Best-effort by design: the authoritative copy of a backup set lives on
     * /data/local/tmp, so a media-store hiccup here must never fail the
     * operation that called it.
     */
    fun deleteUnder(subDir: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = PoxApplication.instance.contentResolver
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$subDir"
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("$relativePath%")
                )
            } else {
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(root, subDir).deleteRecursively()
            }
        }
    }

    private fun writeViaMediaStore(fileName: String, mimeType: String, content: ByteArray): Uri {
        val resolver = PoxApplication.instance.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create $fileName")

        return try {
            resolver.openOutputStream(uri)?.use { it.write(content) }
                ?: throw IllegalStateException("Could not open $fileName for writing")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            // Do not leave an orphaned pending entry behind on failure.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun writeStreamViaMediaStore(subDir: String?, fileName: String, mimeType: String, source: File): Uri {
        val resolver = PoxApplication.instance.contentResolver
        val relativePath = listOfNotNull(
            Environment.DIRECTORY_DOWNLOADS,
            subDir?.takeIf { it.isNotBlank() }
        ).joinToString("/")

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create $fileName")

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open $fileName for writing")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
