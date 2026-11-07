package com.famage.skydrop.model

import com.famage.skydrop.presence.PresenceState

/**
 * Represents the live connection and pairing status of a peer device.
 */
data class PeerStatus(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isTls: Boolean = true,
    val fingerprint: String? = null,
    val isPaired: Boolean = false,
    val isConnected: Boolean = false,
    val authToken: String? = null,
    val presenceState: PresenceState = PresenceState.UNKNOWN,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val lastSeenAt: Long = 0L,
    val capabilities: List<String> = emptyList(),
    val connectionType: String = "wifi"
) {
    val canSend: Boolean
        get() = isPaired && (presenceState == PresenceState.ONLINE || presenceState == PresenceState.BUSY)

    val baseUrl: String
        get() {
            val scheme = if (isTls) "https" else "http"
            return "$scheme://$host:$port"
        }

    fun toDevice(): Device {
        return Device(
            id = id,
            name = name,
            host = host,
            port = port,
            isTls = isTls,
            fingerprint = fingerprint
        )
    }
}
