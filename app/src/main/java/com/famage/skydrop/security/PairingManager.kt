package com.famage.skydrop.security

import android.net.Uri
import android.util.Log
import com.famage.skydrop.model.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
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

        @Volatile
        var activeLocalSession: LocalPairingSession? = null

        fun generateLocalSession(): LocalPairingSession {
            val random = SecureRandom()
            val secret = String.format("%06d", random.nextInt(1000000))
            val expiresAt = System.currentTimeMillis() + 120_000L // 2 minutes
            val session = LocalPairingSession(secret, expiresAt)
            activeLocalSession = session
            return session
        }

        fun validateLocalSession(secret: String): Boolean {
            val session = activeLocalSession ?: return false
            if (System.currentTimeMillis() > session.expiresAtMs) {
                activeLocalSession = null
                return false
            }
            if (session.failedAttempts >= 5) {
                activeLocalSession = null
                return false
            }
            val matches = session.secret == secret
            if (!matches) {
                session.failedAttempts++
                if (session.failedAttempts >= 5) {
                    activeLocalSession = null
                }
            }
            return matches
        }

        private val _pairSuccessEvents = MutableSharedFlow<PairedDevice>(extraBufferCapacity = 8)
        val pairSuccessEvents: SharedFlow<PairedDevice> = _pairSuccessEvents.asSharedFlow()

        fun notifyPairSuccess(device: PairedDevice) {
            _pairSuccessEvents.tryEmit(device)
        }
    }

    suspend fun pairWithDevice(
        host: String,
        port: Int,
        secret: String,
        expectedFingerprint: String?,
        localDeviceName: String,
        isTls: Boolean = (port == 8443)
    ): Result<PairedDevice> = withContext(Dispatchers.IO) {
        try {
            val scheme = if (isTls) "https" else "http"
            val url = "$scheme://$host:$port/pair/confirm"

            val client = if (isTls) createPinningClient(expectedFingerprint) else OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

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
                    isTls = isTls,
                    fingerprint = remoteFingerprint,
                    authToken = authToken
                )

                store.saveDevice(paired)
                Result.success(paired)
            }
        } catch (e: Exception) {
            if (isTls && (e is IOException || e is java.net.SocketTimeoutException)) {
                Log.w(TAG, "HTTPS pair confirmation to $host:$port failed ($e), falling back to HTTP...")
                return@withContext pairWithDevice(
                    host = host,
                    port = port,
                    secret = secret,
                    expectedFingerprint = expectedFingerprint,
                    localDeviceName = localDeviceName,
                    isTls = false
                )
            }
            Log.e(TAG, "Pairing network error", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a pairing request to the remote peer and waits for user approval.
     */
    suspend fun requestPairing(
        host: String,
        port: Int,
        isTls: Boolean,
        localDeviceName: String,
        localDeviceId: String,
        expectedFingerprint: String? = null,
        onStatusUpdate: ((status: String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val useTls = isTls || (port == 8443)
            val activeScheme = if (useTls) "https" else "http"
            val url = "$activeScheme://$host:$port/pair/request"

            val client = if (useTls) createPinningClient(expectedFingerprint) else OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val reqId = java.util.UUID.randomUUID().toString()
            val jsonBody = JSONObject().apply {
                put("request_id", reqId)
                put("device_name", localDeviceName)
                put("device_id", localDeviceId)
                put("device_type", "android")
                put("host", "")
                put("port", 8444)
                put("is_tls", false)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .header("X-SkyDrop-Device-ID", localDeviceId)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            val resp = client.newCall(request).execute()

            resp.use { r ->
                if (!r.isSuccessful) {
                    val err = r.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Pairing request failed (HTTP ${r.code}): $err"))
                }
            }

            onStatusUpdate?.invoke("Waiting for approval...")

            // Poll status up to 45 seconds
            val statusUrl = "$activeScheme://$host:$port/pair/request/status?request_id=$reqId"
            val deadline = System.currentTimeMillis() + 45000L

            while (System.currentTimeMillis() < deadline) {
                delay(1500L)
                val stReq = Request.Builder().url(statusUrl).build()
                try {
                    client.newCall(stReq).execute().use { stResp ->
                        if (stResp.isSuccessful) {
                            val stBody = stResp.body?.string().orEmpty()
                            val stJson = JSONObject(stBody)
                            val status = stJson.optString("status")
                            when (status) {
                                "accepted" -> return@withContext Result.success(reqId)
                                "declined" -> return@withContext Result.failure(IOException("Pairing request was declined by the receiving device"))
                                "cancelled", "expired" -> return@withContext Result.failure(IOException("Pairing request was $status"))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            Result.failure(IOException("Pairing request timed out waiting for approval"))
        } catch (e: Exception) {
            Log.e(TAG, "Request pairing error", e)
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
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // --- Android as Receiver (Stage 1) ---

    data class LocalPairingSession(
        val secret: String,
        val expiresAtMs: Long,
        var authToken: String? = null,
        var failedAttempts: Int = 0
    )

    var activeLocalSession: LocalPairingSession?
        get() = Companion.activeLocalSession
        set(value) { Companion.activeLocalSession = value }

    fun generateLocalSession(): LocalPairingSession = Companion.generateLocalSession()

    fun validateLocalSession(secret: String): Boolean = Companion.validateLocalSession(secret)
}
