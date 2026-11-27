package com.famage.skydrop.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.famage.skydrop.identity.DeviceIdentity
import com.famage.skydrop.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Robust Network Service Discovery manager using Android's NsdManager.
 *
 * Handles:
 * - MulticastLock acquisition and release
 * - Service discovery for _filedrop._tcp and _skydrop._tcp
 * - Serialized FIFO resolve queue with watchdog timeouts (prevents Android's FAILURE_ALREADY_ACTIVE bug)
 * - Self-device filtering (by device ID and local interface IP)
 * - TXT record parsing (tls, fingerprint, device_id, os, version)
 * - Clean teardown and auto-recovery on discovery failure
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var isDiscovering = false

    // Resolve queue to avoid Android NsdManager FAILURE_ALREADY_ACTIVE (error code 3)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resolveTimeoutRunnable: Runnable? = null

    private var retryCount = 0
    private var retryRunnable: Runnable? = null

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_filedrop._tcp"
        val SUPPORTED_SERVICE_TYPES = listOf("_filedrop._tcp", "_skydrop._tcp")
        private const val RESOLVE_TIMEOUT_MS = 4000L
        private const val MAX_RESOLVE_QUEUE_SIZE = 30
        private const val MAX_RETRY_BACKOFF_MS = 10000L
    }

    @Synchronized
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        cancelRetry()
        acquireMulticastLock()
        isDiscovering = true

        for (serviceType in SUPPORTED_SERVICE_TYPES) {
            val listener = createDiscoveryListener(serviceType)
            discoveryListeners[serviceType] = listener
            try {
                Log.d(TAG, "Starting NsdManager discovery for: $serviceType")
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service discovery for $serviceType", e)
                discoveryListeners.remove(serviceType)
            }
        }

        if (discoveryListeners.isEmpty()) {
            isDiscovering = false
            releaseMulticastLock()
            scheduleRetry()
        }
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started successfully for: $regType")
                retryCount = 0
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val cleanType = service.serviceType.trim('.', ' ')
                val expectedTypes = SUPPORTED_SERVICE_TYPES.map { it.trim('.', ' ') }
                if (expectedTypes.none { cleanType.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "Ignoring unrelated service type: ${service.serviceType}")
                    return
                }

                Log.d(TAG, "Service found: ${service.serviceName} (${service.serviceType})")
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                removeDevice(service.serviceName)
            }

            override fun onDiscoveryStopped(stoppedServiceType: String) {
                Log.d(TAG, "Discovery stopped for: $stoppedServiceType")
            }

            override fun onStartDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed for $failedServiceType: $errorCode")
                synchronized(this@NsdDiscoveryManager) {
                    discoveryListeners.remove(failedServiceType)
                    if (discoveryListeners.isEmpty()) {
                        isDiscovering = false
                        releaseMulticastLock()
                        scheduleRetry()
                    }
                }
            }

            override fun onStopDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed for $failedServiceType: $errorCode")
                synchronized(this@NsdDiscoveryManager) {
                    discoveryListeners.remove(failedServiceType)
                }
            }
        }
    }

    @Synchronized
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        if (resolveQueue.size >= MAX_RESOLVE_QUEUE_SIZE) {
            resolveQueue.poll() // Drop oldest to avoid unbound memory
        }
        val alreadyQueued = resolveQueue.any { it.serviceName == serviceInfo.serviceName }
        if (!alreadyQueued) {
            resolveQueue.add(serviceInfo)
        }
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving || resolveQueue.isEmpty() || nsdManager == null) {
            return
        }

        val nextService = resolveQueue.poll() ?: return
        isResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: error code $errorCode")
                finishResolve()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                    handleResolvedService(resolved)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling resolved service: ${resolved.serviceName}", e)
                } finally {
                    finishResolve()
                }
            }
        }

        resolveTimeoutRunnable = Runnable {
            synchronized(this@NsdDiscoveryManager) {
                if (isResolving) {
                    Log.w(TAG, "Resolve timed out for ${nextService.serviceName}")
                    isResolving = false
                    processNextResolve()
                }
            }
        }
        resolveTimeoutRunnable?.let { mainHandler.postDelayed(it, RESOLVE_TIMEOUT_MS) }

        try {
            nsdManager.resolveService(nextService, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error initiating resolve for ${nextService.serviceName}", e)
            finishResolve()
        }
    }

    @Synchronized
    private fun finishResolve() {
        resolveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        resolveTimeoutRunnable = null
        isResolving = false
        processNextResolve()
    }

    private fun handleResolvedService(resolved: NsdServiceInfo) {
        val host = resolved.host?.hostAddress ?: return
        val port = resolved.port
        val name = resolved.serviceName

        var isTls = (port == 8443)
        var fingerprint: String? = null
        val attributes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            resolved.attributes
        } else {
            null
        }

        var peerIdAttr: String? = null
        if (attributes != null) {
            val tlsAttr = attributes["tls"]?.let { String(it) }
            if (tlsAttr != null) {
                isTls = tlsAttr == "true" || tlsAttr == "1"
            }
            fingerprint = attributes["fingerprint"]?.let { String(it) }
            peerIdAttr = attributes["id"]?.let { String(it) }
                ?: attributes["device_id"]?.let { String(it) }
        }

        if (DeviceIdentity.isSelf(context, name, host, port, attributes)) {
            Log.d(TAG, "Ignoring self device discovery: $name at $host:$port")
            return
        }

        val device = Device(
            id = peerIdAttr?.takeIf { it.isNotBlank() } ?: "$host:$port",
            name = name,
            host = host,
            port = port,
            isTls = isTls,
            fingerprint = fingerprint
        )

        Log.i(TAG, "Discovered active peer: ${device.name} ($host:$port, TLS=$isTls)")
        addOrUpdateDevice(device)
    }

    @Synchronized
    fun addManualDevice(host: String, port: Int, name: String = "Manual Peer", isTls: Boolean = false) {
        if (DeviceIdentity.isLocalAddress(host)) {
            Log.w(TAG, "Cannot add self host as manual device: $host")
            return
        }
        val device = Device(
            id = "$host:$port",
            name = name,
            host = host,
            port = port,
            isTls = isTls
        )
        addOrUpdateDevice(device)
    }

    private fun addOrUpdateDevice(device: Device) {
        if (DeviceIdentity.isLocalAddress(device.host)) {
            return
        }
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.id == device.id || (it.host == device.host && it.port == device.port) }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _devices.value = current
    }

    private fun removeDevice(serviceName: String) {
        _devices.value = _devices.value.filter { it.name != serviceName }
    }

    @Synchronized
    fun stopDiscovery() {
        cancelRetry()
        if (!isDiscovering) return

        for ((_, listener) in discoveryListeners) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery listener", e)
            }
        }
        discoveryListeners.clear()

        resolveQueue.clear()
        resolveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        resolveTimeoutRunnable = null
        isResolving = false

        isDiscovering = false
        releaseMulticastLock()
    }

    private fun scheduleRetry() {
        cancelRetry()
        val backoff = (1000L * (1 shl retryCount.coerceAtMost(3))).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        retryCount++
        Log.d(TAG, "Scheduling discovery retry #${retryCount} in ${backoff}ms")
        retryRunnable = Runnable {
            startDiscovery()
        }
        mainHandler.postDelayed(retryRunnable!!, backoff)
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("SkyDropMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock", e)
        }
    }
}
