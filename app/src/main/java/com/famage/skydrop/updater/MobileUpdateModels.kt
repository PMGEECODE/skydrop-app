package com.famage.skydrop.updater

/**
 * Metadata for a downloadable asset attached to a GitHub release.
 */
data class MobileReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * Result of checking GitHub releases for updates.
 */
data class MobileUpdateInfo(
    val isUpdateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAt: String,
    val targetAsset: MobileReleaseAsset?,
    val checksumAsset: MobileReleaseAsset?,
    val releaseHtmlUrl: String
)

/**
 * Real-time progress updates during APK download.
 */
data class MobileUpdateDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Int,
    val isComplete: Boolean = false,
    val isVerifyingSha256: Boolean = false,
    val error: String? = null
)
