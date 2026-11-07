package com.famage.skydrop.security

import android.net.Uri
import android.util.Log
import com.famage.skydrop.model.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PairingUriData(
    val deviceName: String,
    val host: String,
    val port: Int,
    val secret: String,
    val fingerprint: String
)

class PairingManager(private val store: PairedDeviceStore) {

    companion object {
        private const val TAG = "PairingManager"

        fun parsePairingUri(rawUri: String): PairingUriData? {
            return try {
                val javaUri = java.net.URI(rawUri)
                if (javaUri.scheme != "skydrop" || javaUri.host != "pair") return null

                val query = javaUri.rawQuery ?: return null
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        parts[0] to ""
                    }
                }

                val device = params["device"] ?: return null
                val host = params["host"].orEmpty()
                val fingerprint = params["fingerprint"].orEmpty()
                val port = params["port"]?.toIntOrNull() ?: 8443
                val secret = params["secret"] ?: return null

                PairingUriData(
                    deviceName = device,
                    host = host,
                    port = port,
                    secret = secret,
                    fingerprint = fingerprint
                )
            } catch (_: Exception) {
                null
            }
        }

        fun computeFingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            val der = cert.encoded
            val digest = md.digest(der)
            return digest.joinToString(":") { "%02X".format(it) }
        }
    }

    suspend fun pairWithDevice(
        host: String,
        port: Int,
        secret: String,
        expectedFingerprint: String?,
        localDeviceName: String
    ): Result<PairedDevice> = withContext(Dispatchers.IO) {
        try {
            val client = createPinningClient(expectedFingerprint)
            val url = "https://$host:$port/pair/confirm"

            val jsonBody = JSONObject().apply {
                put("device_name", localDeviceName)
                put("fingerprint", "")
                put("secret", secret)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Pairing failed (HTTP ${response.code}): $err"))
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val authToken = json.getString("auth_token")
                val deviceId = json.getString("device_id")
                val remoteName = json.optString("device_name", "Remote Peer")
                val remoteFingerprint = json.optString("fingerprint", expectedFingerprint.orEmpty())

                val paired = PairedDevice(
                    id = deviceId,
                    name = remoteName,
                    host = host,
                    port = port,
                    isTls = true,
                    fingerprint = remoteFingerprint,
                    authToken = authToken
                )

                store.saveDevice(paired)
                Result.success(paired)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing network error", e)
            Result.failure(e)
        }
    }

    private fun createPinningClient(expectedFingerprint: String?): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (expectedFingerprint.isNullOrBlank()) return // fallback if no fingerprint provided

                val leafCert = chain?.firstOrNull() ?: throw IOException("Server presented empty certificate chain")
                val actualFingerprint = computeFingerprint(leafCert)
                if (!actualFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                    throw IOException("Certificate pinning mismatch: expected $expectedFingerprint, got $actualFingerprint")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
