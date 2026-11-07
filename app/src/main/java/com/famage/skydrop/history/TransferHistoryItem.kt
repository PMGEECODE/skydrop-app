package com.famage.skydrop.history

data class TransferHistoryItem(
    val id: String = "hist_${System.currentTimeMillis()}_${(1000..9999).random()}",
    val timestamp: Long = System.currentTimeMillis(),
    val direction: String, // "inbound" or "outbound"
    val itemType: String,  // "file" or "text"
    val name: String,
    val size: Long,
    val peerName: String,
    val status: String,    // "completed" or "failed"
    val errorMessage: String? = null,
    val uri: String? = null,
    val mimeType: String? = null
)
