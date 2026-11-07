package com.famage.skydrop.model

/**
 * Represents a device successfully paired with a persistent authorization token.
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isTls: Boolean = true,
    val fingerprint: String,
    val authToken: String,
    val pairedAt: Long = System.currentTimeMillis()
)
