package com.famage.skydrop.identity

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

object DeviceIdentity {

    private const val PREFS_NAME = "skydrop_identity_prefs"
    private const val KEY_DEVICE_ID = "unique_device_id"

    @Synchronized
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }
            id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun isLocalAddress(host: String): Boolean {
        if (host.isBlank()) return false
        val cleanHost = host.substringBefore("%").trim()
        if (cleanHost == "127.0.0.1" || cleanHost == "::1" || cleanHost.equals("localhost", ignoreCase = true) || cleanHost == "0.0.0.0" || cleanHost == "::") {
            return true
        }

        try {
            val targetAddr = InetAddress.getByName(cleanHost)
            if (targetAddr.isLoopbackAddress || targetAddr.isAnyLocalAddress) {
                return true
            }

            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in interfaces) {
                for (addr in iface.inetAddresses) {
                    val addrHost = addr.hostAddress?.substringBefore("%")?.trim()
                    if (addrHost != null && addrHost.equals(cleanHost, ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            // Parsing failure or network interface access error
        }
        return false
    }

    fun isSelf(
        context: Context,
        name: String,
        host: String,
        port: Int,
        attributes: Map<String, ByteArray>? = null
    ): Boolean {
        // 1. Explicit device_id attribute match
        val attrId = attributes?.get("device_id")?.let { String(it) }
        if (!attrId.isNullOrBlank() && attrId == getDeviceId(context)) {
            return true
        }

        // 2. Loopback or any address bound to local network interfaces
        if (isLocalAddress(host)) {
            return true
        }

        return false
    }
}
