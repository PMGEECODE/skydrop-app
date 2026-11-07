package com.famage.skydrop.model

import android.net.Uri

/**
 * Represents a file or text content queued for sending.
 */
data class SharedItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long
)
