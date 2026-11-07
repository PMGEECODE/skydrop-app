package com.famage.skydrop.presence

/**
 * Presence states for remote peers.
 * PAIRED IS NOT ONLINE: pairing is a trust relationship on disk; presence is a live runtime state.
 */
enum class PresenceState {
    UNKNOWN,
    CHECKING,
    ONLINE,
    BUSY,
    DEGRADED,
    OFFLINE,
    ERROR
}

object PresenceConstants {
    const val CAP_HEALTH_V1 = "health_v1"
    const val CAP_RESUMABLE_UPLOAD = "resumable_upload"
    const val CAP_CHECKSUM_V1 = "checksum_v1"
    const val CAP_BATCH_TRANSFER = "batch_transfer"

    val DEFAULT_CAPABILITIES = listOf(
        CAP_HEALTH_V1,
        CAP_RESUMABLE_UPLOAD,
        CAP_CHECKSUM_V1,
        CAP_BATCH_TRANSFER
    )

    // Standardized error codes
    const val ERR_AUTH_FAILED = "ERR_AUTH_FAILED"
    const val ERR_UNREACHABLE = "ERR_UNREACHABLE"
    const val ERR_VERSION_MISMATCH = "ERR_VERSION_MISMATCH"
    const val ERR_CERT_MISMATCH = "ERR_CERT_MISMATCH"
    const val ERR_DEVIATED_IP = "ERR_DEVIATED_IP"
    const val ERR_DEVICE_BUSY = "ERR_DEVICE_BUSY"
    const val ERR_NOT_PAIRED = "ERR_NOT_PAIRED"
    const val ERR_TOKEN_REVOKED = "ERR_TOKEN_REVOKED"

    const val DEFAULT_FOREGROUND_INTERVAL_MS = 10000L
    const val DEFAULT_BACKGROUND_INTERVAL_MS = 60000L
    const val DEFAULT_PROBE_TIMEOUT_MS = 2500L
    const val INITIAL_BACKOFF_MS = 1000L
    const val MAX_BACKOFF_MS = 30000L

    const val MISSED_BEATS_DEGRADED_THRESHOLD = 2
    const val MISSED_BEATS_OFFLINE_THRESHOLD = 3
}

data class HealthResponse(
    val device_id: String,
    val device_name: String,
    val app_version: String,
    val protocol_version: String,
    val capabilities: List<String> = emptyList(),
    val state: String = "idle",
    val fingerprint: String? = null
)

data class LeaveRequest(
    val device_id: String,
    val reason: String = "user_closed"
)

data class PeerPresence(
    val peerId: String,
    val deviceName: String,
    val state: PresenceState = PresenceState.UNKNOWN,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val lastSeenAt: Long = 0L,
    val lastCheckedAt: Long = 0L,
    val latencyMs: Long = 0L,
    val missedBeats: Int = 0,
    val connectionType: String = "wifi",
    val host: String = "",
    val port: Int = 0,
    val isTls: Boolean = true,
    val fingerprint: String? = null,
    val capabilities: List<String> = emptyList(),
    val appVersion: String? = null,
    val protocolVersion: String? = null,
    val isPaired: Boolean = false
) {
    val canSend: Boolean
        get() = isPaired && (state == PresenceState.ONLINE || state == PresenceState.BUSY)
}
