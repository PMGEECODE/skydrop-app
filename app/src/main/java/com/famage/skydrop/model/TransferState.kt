package com.famage.skydrop.model

/**
 * State of an ongoing or completed transfer.
 */
sealed class TransferState {
    object Idle : TransferState()
    object Preparing : TransferState()
    data class Uploading(
        val currentFileName: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : TransferState()
    data class Success(val message: String) : TransferState()
    data class Error(val errorMessage: String) : TransferState()
}
