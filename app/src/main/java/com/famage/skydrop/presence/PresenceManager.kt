package com.famage.skydrop.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.famage.skydrop.identity.DeviceIdentity
import com.famage.skydrop.model.Device
import com.famage.skydrop.security.PairedDeviceStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.random.Random

/**
 * PresenceManager manages active peer presence tracking, authenticated heartbeats,
 * network callback re-checks, and graceful departure for the Android app.
 */
class PresenceManager(
    private val context: Context,
    private val pairedDeviceStore: PairedDeviceStore
) {
    companion object {
        private const val TAG = "PresenceManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _presences = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    val presences: StateFlow<Map<String, PeerPresence>> = _presences.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, PeerPresence>()
    private val backoffMap = ConcurrentHashMap<String, Long>()
    private var heartbeatJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // OkHttpClient with pooled keep-alive connections and TLS trust for local SkyDrop peers
    private val httpClient: OkHttpClient by lazy {
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

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionPool(ConnectionPool(10, 90, TimeUnit.SECONDS))
            .connectTimeout(PresenceConstants.DEFAULT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PresenceConstants.DEFAULT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    var isTransferring: ((peerId: String) -> Boolean)? = null
    var onNetworkChangedListener: (() -> Unit)? = null

    fun start() {
        if (heartbeatJob != null) return
        registerNetworkCallback()
        startHeartbeatLoop()
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterNetworkCallback()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob = scope.launch {
            while (isActive) {
                val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isDeviceIdleMode == true
                } else false

                val interval = if (isIdle) {
                    PresenceConstants.DEFAULT_BACKGROUND_INTERVAL_MS
                } else {
                    PresenceConstants.DEFAULT_FOREGROUND_INTERVAL_MS
                }

                probeAllPeers()

                val jitter = Random.nextLong(0, 500)
                delay(interval + jitter)
            }
        }
    }

    private suspend fun probeAllPeers() = withContext(Dispatchers.IO) {
        val paired = pairedDeviceStore.pairedDevices.value
        val jobs = paired.map { device ->
            launch {
                val backoff = backoffMap[device.id] ?: 0L
                val lastChecked = peerMap[device.id]?.lastCheckedAt ?: 0L
                val now = System.currentTimeMillis()

                if (backoff > 0 && now - lastChecked < backoff) {
                    return@launch // Honor exponential backoff
                }

                probePeer(
                    peerId = device.id,
                    deviceName = device.name,
                    authToken = device.authToken,
                    fingerprint = device.fingerprint,
                    overrideHost = device.host,
                    overridePort = device.port,
                    overrideIsTls = device.isTls
                )
            }
        }
        jobs.joinAll()
    }

    fun retryPeer(peerId: String) {
        scope.launch {
            backoffMap[peerId] = 0L
            val existingPeer = peerMap[peerId]
            val paired = pairedDeviceStore.pairedDevices.value.firstOrNull {
                it.id == peerId ||
                (it.host.isNotBlank() && (it.host == peerId || it.host == existingPeer?.host)) ||
                it.name.equals(existingPeer?.deviceName, ignoreCase = true)
            }
            if (paired != null) {
                backoffMap[paired.id] = 0L
                val host = paired.host.ifBlank { existingPeer?.host ?: "" }
                val port = if (paired.port > 0) paired.port else (existingPeer?.port ?: 8443)
                probePeer(
                    peerId = paired.id,
                    deviceName = paired.name,
                    authToken = paired.authToken,
                    fingerprint = paired.fingerprint,
                    overrideHost = host,
                    overridePort = port,
                    overrideIsTls = paired.isTls
                )
                if (peerId != paired.id) {
                    probePeer(
                        peerId = peerId,
                        deviceName = paired.name,
                        authToken = paired.authToken,
                        fingerprint = paired.fingerprint,
                        overrideHost = host,
                        overridePort = port,
                        overrideIsTls = paired.isTls
                    )
                }
            } else {
                val peer = peerMap[peerId]
                if (peer != null) {
                    probePeer(
                        peerId = peer.peerId,
                        deviceName = peer.deviceName,
                        authToken = "",
                        fingerprint = null,
                        overrideHost = peer.host,
                        overridePort = peer.port,
                        overrideIsTls = peer.isTls
                    )
                }
            }
        }
    }

    suspend fun probePeer(
        peerId: String,
        deviceName: String,
        authToken: String,
        fingerprint: String?,
        overrideHost: String? = null,
        overridePort: Int? = null,
        overrideIsTls: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        // If actively transferring with this peer, count progress as liveness
        if (isTransferring?.invoke(peerId) == true) {
            recordActivity(peerId)
            return@withContext
        }

        val existing = peerMap[peerId]
        val host = overrideHost?.takeIf { it.isNotBlank() } ?: existing?.host ?: ""
        val port = overridePort?.takeIf { it > 0 } ?: existing?.port ?: 0
        val isTls = overrideIsTls ?: existing?.isTls ?: (port == 8443)

        // Resolve auth token from paired store if not provided
        var effectiveAuthToken = authToken
        var effectiveFingerprint = fingerprint
        if (effectiveAuthToken.isBlank()) {
            val matchingPaired = pairedDeviceStore.pairedDevices.value.firstOrNull {
                it.id == peerId ||
                (it.host.isNotBlank() && it.host == host) ||
                it.name.equals(deviceName, ignoreCase = true)
            }
            if (matchingPaired != null) {
                effectiveAuthToken = matchingPaired.authToken
                effectiveFingerprint = matchingPaired.fingerprint
            }
        }

        if (host.isBlank() || port <= 0) {
            markMissedBeat(peerId, deviceName, PresenceConstants.ERR_UNREACHABLE, "No reachable endpoint for peer")
            return@withContext
        }

        val scheme = if (isTls) "https" else "http"
        val url = "$scheme://$host:$port/health"
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $effectiveAuthToken")
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.code == 401 || response.code == 403) {
                    updatePeerState(
                        peerId, deviceName, PresenceState.ERROR,
                        PresenceConstants.ERR_AUTH_FAILED, "Pairing token rejected by remote device",
                        latency = latency, host = host, port = port, isPaired = effectiveAuthToken.isNotBlank()
                    )
                    return@use
                }

                if (!response.isSuccessful) {
                    markMissedBeat(peerId, deviceName, "HTTP_${response.code}", "Health returned HTTP ${response.code}")
                    return@use
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val remoteState = json.optString("state", "idle")
                val caps = mutableListOf<String>()
                json.optJSONArray("capabilities")?.let { arr ->
                    for (i in 0 until arr.length()) caps.add(arr.getString(i))
                }

                val state = if (remoteState == "transferring") PresenceState.BUSY else PresenceState.ONLINE
                backoffMap[peerId] = 0L // Reset backoff on success

                updatePeerState(
                    peerId = peerId,
                    deviceName = json.optString("device_name", deviceName),
                    state = state,
                    latency = latency,
                    host = host,
                    port = port,
                    isPaired = true,
                    capabilities = caps,
                    appVersion = json.optString("app_version"),
                    protocolVersion = json.optString("protocol_version")
                )
            }
        } catch (e: Exception) {
            val errCode = when (e) {
                is ConnectException -> PresenceConstants.ERR_UNREACHABLE
                is SocketTimeoutException -> "ERR_TIMEOUT"
                else -> "ERR_IO"
            }
            markMissedBeat(peerId, deviceName, errCode, e.message ?: "Connection failed")
        }
    }

    private fun markMissedBeat(peerId: String, deviceName: String, errCode: String, errMsg: String) {
        val existing = peerMap[peerId]
        val missed = (existing?.missedBeats ?: 0) + 1

        val currBackoff = backoffMap[peerId] ?: 0L
        val nextBackoff = if (currBackoff == 0L) {
            PresenceConstants.INITIAL_BACKOFF_MS
        } else {
            (currBackoff * 2).coerceAtMost(PresenceConstants.MAX_BACKOFF_MS)
        }
        backoffMap[peerId] = nextBackoff

        val nextState = when {
            missed >= PresenceConstants.MISSED_BEATS_OFFLINE_THRESHOLD -> PresenceState.OFFLINE
            missed == PresenceConstants.MISSED_BEATS_DEGRADED_THRESHOLD -> PresenceState.DEGRADED
            existing?.state == PresenceState.UNKNOWN -> PresenceState.CHECKING
            else -> existing?.state ?: PresenceState.CHECKING
        }

        updatePeerState(
            peerId = peerId,
            deviceName = deviceName,
            state = nextState,
            errorCode = errCode,
            errorMessage = errMsg,
            missedBeats = missed,
            host = existing?.host ?: "",
            port = existing?.port ?: 0,
            isPaired = true
        )
    }

    private fun updatePeerState(
        peerId: String,
        deviceName: String,
        state: PresenceState,
        errorCode: String? = null,
        errorMessage: String? = null,
        latency: Long = 0L,
        missedBeats: Int = 0,
        host: String = "",
        port: Int = 0,
        isPaired: Boolean = true,
        capabilities: List<String> = emptyList(),
        appVersion: String? = null,
        protocolVersion: String? = null
    ) {
        val now = System.currentTimeMillis()
        val presence = PeerPresence(
            peerId = peerId,
            deviceName = deviceName,
            state = state,
            errorCode = errorCode,
            errorMessage = errorMessage,
            lastSeenAt = if (state == PresenceState.ONLINE || state == PresenceState.BUSY) now else (peerMap[peerId]?.lastSeenAt ?: 0L),
            lastCheckedAt = now,
            latencyMs = latency,
            missedBeats = missedBeats,
            host = host,
            port = port,
            isPaired = isPaired,
            capabilities = capabilities,
            appVersion = appVersion,
            protocolVersion = protocolVersion
        )
        peerMap[peerId] = presence
        _presences.value = HashMap(peerMap)
    }

    fun recordActivity(peerId: String) {
        val p = peerMap[peerId] ?: return
        val now = System.currentTimeMillis()
        val updated = p.copy(
            state = if (p.state == PresenceState.DEGRADED || p.state == PresenceState.OFFLINE) PresenceState.ONLINE else p.state,
            lastSeenAt = now,
            lastCheckedAt = now,
            missedBeats = 0,
            errorCode = null,
            errorMessage = null
        )
        peerMap[peerId] = updated
        _presences.value = HashMap(peerMap)
    }

    fun onDiscoveredEndpoint(peerId: String, name: String, host: String, port: Int, isTls: Boolean = (port == 8443)) {
        val existing = peerMap[peerId]
        peerMap[peerId] = (existing ?: PeerPresence(peerId, name)).copy(
            host = host,
            port = port,
            deviceName = name,
            isTls = isTls
        )

        // Also update any paired device that matches this host, ID, or name
        val pairedMatches = pairedDeviceStore.pairedDevices.value.filter {
            it.id == peerId || (it.host.isNotBlank() && it.host == host) || it.name.equals(name, ignoreCase = true)
        }
        pairedMatches.forEach { p ->
            pairedDeviceStore.updateDeviceEndpoint(p.id, host, port)
            val existingPaired = peerMap[p.id]
            peerMap[p.id] = (existingPaired ?: PeerPresence(p.id, p.name)).copy(
                host = host,
                port = port,
                deviceName = p.name,
                isTls = isTls
            )
            // Immediately probe the discovered paired peer
            scope.launch {
                probePeer(
                    peerId = p.id,
                    deviceName = p.name,
                    authToken = p.authToken,
                    fingerprint = p.fingerprint,
                    overrideHost = host,
                    overridePort = port,
                    overrideIsTls = isTls
                )
            }
        }
        _presences.value = HashMap(peerMap)
    }

    fun onFastOfflineHint(serviceName: String) {
        val matched = peerMap.values.firstOrNull { it.deviceName.equals(serviceName, ignoreCase = true) } ?: return
        scope.launch {
            // Immediate authenticated probe
            val paired = pairedDeviceStore.pairedDevices.value.firstOrNull { it.id == matched.peerId || it.name.equals(serviceName, ignoreCase = true) }
            if (paired != null) {
                probePeer(
                    peerId = paired.id,
                    deviceName = paired.name,
                    authToken = paired.authToken,
                    fingerprint = paired.fingerprint,
                    overrideHost = paired.host,
                    overridePort = paired.port,
                    overrideIsTls = paired.isTls
                )
            }
        }
    }

    fun triggerImmediateCheck(peerId: String? = null) {
        scope.launch {
            val paired = pairedDeviceStore.pairedDevices.value
            if (peerId != null) {
                paired.firstOrNull { it.id == peerId }?.let {
                    backoffMap[it.id] = 0L
                    probePeer(
                        peerId = it.id,
                        deviceName = it.name,
                        authToken = it.authToken,
                        fingerprint = it.fingerprint,
                        overrideHost = it.host,
                        overridePort = it.port,
                        overrideIsTls = it.isTls
                    )
                }
            } else {
                paired.forEach { backoffMap[it.id] = 0L }
                probeAllPeers()
            }
        }
    }

    fun sendLeaveToAll() {
        val paired = pairedDeviceStore.pairedDevices.value
        val myDeviceId = DeviceIdentity.getDeviceId(context)
        scope.launch {
            paired.forEach { peer ->
                val p = peerMap[peer.id]
                val host = p?.host?.takeIf { it.isNotBlank() } ?: peer.host
                val port = if ((p?.port ?: 0) > 0) p!!.port else peer.port
                val isTls = p?.isTls ?: peer.isTls ?: (port == 8443)
                if (host.isNotBlank() && port > 0) {
                    try {
                        val jsonStr = JSONObject().put("device_id", myDeviceId).put("reason", "app_closed").toString()
                        val body = jsonStr.toRequestBody("application/json".toMediaTypeOrNull())
                        val scheme = if (isTls) "https" else "http"
                        val req = Request.Builder()
                            .url("$scheme://$host:$port/presence/leave")
                            .header("Authorization", "Bearer ${peer.authToken}")
                            .post(body)
                            .build()
                        httpClient.newCall(req).execute().close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun exportDiagnostics(): String {
        val obj = JSONObject()
        obj.put("app_version", "1.0.0")
        obj.put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        obj.put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
        obj.put("timestamp", System.currentTimeMillis())

        val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isDeviceIdleMode == true else false
        val isIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true else true
        obj.put("battery_optimization_ignored", isIgnored)
        obj.put("device_in_doze", isIdle)

        val peersArr = JSONArray()
        peerMap.values.forEach { p ->
            val pObj = JSONObject()
            pObj.put("id", p.peerId)
            pObj.put("name", p.deviceName)
            pObj.put("state", p.state.name)
            pObj.put("host", p.host)
            pObj.put("port", p.port)
            pObj.put("latency_ms", p.latencyMs)
            pObj.put("missed_beats", p.missedBeats)
            pObj.put("last_seen_at", p.lastSeenAt)
            pObj.put("error_code", p.errorCode ?: "")
            pObj.put("error_message", p.errorMessage ?: "")
            peersArr.put(pObj)
        }
        obj.put("peers", peersArr)
        return obj.toString(2)
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available, triggering immediate presence re-check")
                onNetworkChangedListener?.invoke()
                triggerImmediateCheck()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost, marking peers for re-check")
                onNetworkChangedListener?.invoke()
                triggerImmediateCheck()
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }
}
