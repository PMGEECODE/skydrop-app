package com.famage.skydrop.clipboard

/**
 * Represents a synchronized clipboard text entry.
 */
data class ClipboardItem(
    val id: String,
    val text: String,
    val sourceDevice: String,
    val createdAt: Long,
    val pinned: Boolean = false,
    val originId: String = id
)
