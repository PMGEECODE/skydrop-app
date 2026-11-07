package com.famage.skydrop.model

/**
 * Represents a discovered or manually configured peer device.
 */
data class Device(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isTls: Boolean = true,
    val fingerprint: String? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    val baseUrl: String
        get() {
            val scheme = if (isTls) "https" else "http"
            return "$scheme://$host:$port"
        }
}
