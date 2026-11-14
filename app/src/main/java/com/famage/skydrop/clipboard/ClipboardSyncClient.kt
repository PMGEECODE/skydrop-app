package com.famage.skydrop.clipboard

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ClipboardSyncClient {

    companion object {
        private const val TAG = "ClipboardSyncClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    private val client: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    suspend fun postItem(baseUrl: String, authToken: String?, item: ClipboardItem): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/clipboard/add"
                val json = JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("source_device", item.sourceDevice)
                    put("origin_id", item.originId)
                    put("pinned", item.pinned)
                }.toString()

                val builder = Request.Builder()
                    .url(url)
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))

                if (!authToken.isNullOrBlank()) {
                    builder.addHeader("Authorization", "Bearer $authToken")
                }

                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return@withContext Result.failure(
                            IOException("POST /clipboard/add failed (HTTP ${resp.code}): $body")
                        )
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.w(TAG, "postItem failed to $baseUrl: ${e.message}")
                Result.failure(e)
            }
        }

    suspend fun syncIds(baseUrl: String, authToken: String?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/clipboard/sync"
                val builder = Request.Builder()
                    .url(url)
                    .get()

                if (!authToken.isNullOrBlank()) {
                    builder.addHeader("Authorization", "Bearer $authToken")
                }

                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("GET /clipboard/sync failed with HTTP ${resp.code}")
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    val array = obj.optJSONArray("ids") ?: JSONArray()
                    val ids = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        ids.add(array.getString(i))
                    }
                    Result.success(ids)
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncIds failed to $baseUrl: ${e.message}")
                Result.failure(e)
            }
        }

    suspend fun pushMissing(
        baseUrl: String,
        authToken: String?,
        items: List<ClipboardItem>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(Unit)

        try {
            val url = "$baseUrl/clipboard/push"
            val array = JSONArray()
            for (item in items) {
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("source_device", item.sourceDevice)
                    put("origin_id", item.originId)
                    put("pinned", item.pinned)
                })
            }
            val json = JSONObject().apply {
                put("items", array)
            }.toString()

            val builder = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON_MEDIA_TYPE))

            if (!authToken.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $authToken")
            }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException("POST /clipboard/push failed (HTTP ${resp.code}): $body")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushMissing failed to $baseUrl: ${e.message}")
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
