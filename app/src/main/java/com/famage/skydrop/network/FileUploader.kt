package com.famage.skydrop.network

import android.content.ContentResolver
import android.util.Log
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.SharedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FileUploader(private val contentResolver: ContentResolver) {

    companion object {
        private const val TAG = "FileUploader"
        private const val CHUNK_SIZE = 64 * 1024 // 64 KiB
    }

    private val client: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    private fun getServerOffset(baseUrl: String, filename: String, authToken: String?): Long {
        return try {
            val headUrl = "$baseUrl/upload?filename=$filename"
            val builder = Request.Builder().url(headUrl).head().addHeader("X-Filename", filename)
            if (!authToken.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $authToken")
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Upload-Offset")?.toLongOrNull() ?: 0L
                } else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Streams a file to the destination device with real-time progress callbacks and auto-resume.
     */
    suspend fun uploadFile(
        device: Device,
        item: SharedItem,
        authToken: String? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long, percent: Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${device.baseUrl}/upload"
            val mediaType = (item.mimeType.ifEmpty { "application/octet-stream" }).toMediaTypeOrNull()

            val serverOffset = getServerOffset(device.baseUrl, item.name, authToken)
            val offset = if (serverOffset > 0 && serverOffset < item.size) serverOffset else 0L

            val requestBody = object : RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = if (item.size > 0) item.size - offset else -1L

                override fun writeTo(sink: BufferedSink) {
                    val inputStream = contentResolver.openInputStream(item.uri)
                        ?: throw IOException("Unable to open input stream for URI: ${item.uri}")

                    inputStream.use { stream ->
                        if (offset > 0) {
                            var skipped = 0L
                            while (skipped < offset) {
                                val s = stream.skip(offset - skipped)
                                if (s <= 0) break
                                skipped += s
                            }
                        }

                        val buffer = ByteArray(CHUNK_SIZE)
                        var bytesWritten = offset
                        var read: Int

                        while (stream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            bytesWritten += read
                            val percent = if (item.size > 0) {
                                ((bytesWritten * 100) / item.size).toInt()
                            } else 0
                            onProgress?.invoke(bytesWritten, item.size, percent)
                        }
                    }
                }
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("X-Filename", item.name)
                .post(requestBody)

            if (offset > 0) {
                requestBuilder.addHeader("Upload-Offset", offset.toString())
                requestBuilder.addHeader("X-Total-Size", item.size.toString())
            }

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException("Upload failed with HTTP ${response.code}: $errBody")
                    )
                }
                val body = response.body?.string().orEmpty()
                Result.success(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "File upload failed", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads multiple files in sequence, tracking overall batch progress.
     */
    suspend fun uploadFiles(
        device: Device,
        items: List<SharedItem>,
        authToken: String? = null,
        onBatchProgress: (currentIndex: Int, total: Int, name: String, bytes: Long, totalBytes: Long) -> Unit
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val completed = mutableListOf<String>()
        for ((index, item) in items.withIndex()) {
            val result = uploadFile(device, item, authToken) { written, total, _ ->
                onBatchProgress(index + 1, items.size, item.name, written, total)
            }
            if (result.isSuccess) {
                completed.add(item.name)
            } else {
                return@withContext Result.failure(
                    result.exceptionOrNull() ?: IOException("Failed to upload ${item.name}")
                )
            }
        }
        Result.success(completed)
    }

    /**
     * Sends a text snippet or URL to the peer.
     */
    suspend fun sendText(device: Device, text: String, authToken: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${device.baseUrl}/text"
            val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
            val jsonPayload = "{\"text\":\"$escapedText\"}"
            val requestBody = jsonPayload.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Send text failed with HTTP ${response.code}")
                    )
                }
                Result.success(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send text failed", e)
            Result.failure(e)
        }
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.MINUTES) // allow large file transfers
            .readTimeout(60, TimeUnit.MINUTES)
            .build()
    }
}
