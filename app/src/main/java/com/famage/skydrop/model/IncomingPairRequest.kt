package com.famage.skydrop.model

data class IncomingPairRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "desktop",
    val host: String = "",
    val port: Int = 8443,
    val isTls: Boolean = true,
    val fingerprint: String = "",
    val expiresAtMs: Long = System.currentTimeMillis() + 90000L,
    var status: String = "pending"
)
