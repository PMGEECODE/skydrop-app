package com.famage.skydrop.receiver

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

data class SaveResult(
    val uri: Uri,
    val bytesWritten: Long,
    val mimeType: String
)

class ScopedStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "ScopedStorageManager"
        private const val CHUNK_SIZE = 64 * 1024
        private const val SUBFOLDER = "SkyDrop"

        fun sanitizeFilename(name: String): String {
            val normalized = name.trim().replace("\\", "/")
            val clean = File(normalized).name
                .replace("/", "_")
                .replace("..", "_")
            return if (clean.isBlank()) "received_file_${System.currentTimeMillis()}" else clean
        }
    }

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Streams incoming bytes directly to the Android Downloads/SkyDrop directory using scoped storage.
     */
    fun saveStream(
        rawFilename: String,
        inputStream: InputStream,
        declaredSize: Long,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): Result<Uri> {
        return saveStreamWithResult(rawFilename, inputStream, declaredSize, onProgress).map { it.uri }
    }

    fun saveStreamWithResult(
        rawFilename: String,
        inputStream: InputStream,
        declaredSize: Long,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): Result<SaveResult> {
        val sanitizedName = sanitizeFilename(rawFilename)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(sanitizedName, inputStream, onProgress)
        } else {
            saveWithLegacyStorage(sanitizedName, inputStream, onProgress)
        }
    }

    private fun saveWithMediaStore(
        filename: String,
        inputStream: InputStream,
        onProgress: ((bytesWritten: Long) -> Unit)?
    ): Result<SaveResult> {
        val ext = File(filename).extension
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: return Result.failure(IOException("Failed to create MediaStore entry for $filename"))

        try {
            var bytesWritten = 0L
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(CHUNK_SIZE)
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten)
                }
                outputStream.flush()
            } ?: throw IOException("Failed to open output stream for $uri")

            // Mark file as complete / ready for other apps
            val updateValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.update(uri, updateValues, null, null)

            return Result.success(SaveResult(uri, bytesWritten, mimeType))
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming to MediaStore, deleting pending entry", e)
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
            return Result.failure(e)
        }
    }

    private fun saveWithLegacyStorage(
        filename: String,
        inputStream: InputStream,
        onProgress: ((bytesWritten: Long) -> Unit)?
    ): Result<SaveResult> {
        return try {
            val ext = File(filename).extension
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                ?: "application/octet-stream"

            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, SUBFOLDER).apply { mkdirs() }
            val tempFile = File.createTempFile(".upload-", ".tmp", targetDir)
            val finalFile = File(targetDir, filename)

            var bytesWritten = 0L
            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(CHUNK_SIZE)
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten)
                }
                fos.flush()
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return Result.failure(IOException("Failed to rename temporary file to ${finalFile.name}"))
            }

            MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
            Result.success(SaveResult(Uri.fromFile(finalFile), bytesWritten, mimeType))
        } catch (e: Exception) {
            Log.e(TAG, "Legacy storage write failed", e)
            Result.failure(e)
        }
    }
}
