package com.famage.skydrop.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.famage.skydrop.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_filedrop._tcp."
    }

    @Synchronized
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started for: $regType")
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                removeDevice(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            discoveryListener = listener
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service discovery", e)
            releaseMulticastLock()
            discoveryListener = null
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val port = resolved.port
                val name = resolved.serviceName

                var isTls = true
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

                if (com.famage.skydrop.identity.DeviceIdentity.isSelf(context, name, host, port, attributes)) {
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

                addOrUpdateDevice(device)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error initiating resolve for ${serviceInfo.serviceName}", e)
        }
    }

    @Synchronized
    fun addManualDevice(host: String, port: Int, name: String = "Manual Peer", isTls: Boolean = false) {
        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(host)) {
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
        if (com.famage.skydrop.identity.DeviceIdentity.isLocalAddress(device.host)) {
            return
        }
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.id == device.id }
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
        if (!isDiscovering) return

        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        isDiscovering = false
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("SkyDropMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
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
