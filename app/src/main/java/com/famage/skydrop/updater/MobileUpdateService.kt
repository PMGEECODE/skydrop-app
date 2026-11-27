package com.famage.skydrop.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Service managing GitHub release checking, APK asset selection, download with SHA-256 verification,
 * and prompting the Android package installer for SkyDrop Mobile.
 */
class MobileUpdateService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "MobileUpdateService"

        @Volatile
        private var hasCheckedOnLaunch = false

        fun resetLaunchCheckFlag() {
            hasCheckedOnLaunch = false
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.45"
        } catch (_: Exception) {
            "1.0.45"
        }
    }

    suspend fun checkUpdateOnLaunch(force: Boolean = false): MobileUpdateInfo? {
        if (!force && hasCheckedOnLaunch) {
            Log.d(TAG, "Already checked for updates on this app session. Skipping.")
            return null
        }
        hasCheckedOnLaunch = true
        return checkGitHubReleases()
    }

    suspend fun checkGitHubReleases(): MobileUpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName()
        try {
            val request = Request.Builder()
                .url(MobileUpdateConfig.API_RELEASES_URL)
                .header("User-Agent", MobileUpdateConfig.USER_AGENT)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful && body.isNotBlank()) {
                val releasesArray = JSONArray(body)
                var bestCandidateRelease: JSONObject? = null
                var bestCandidateVersion = ""
                var bestTargetAsset: MobileReleaseAsset? = null
                var bestChecksumAsset: MobileReleaseAsset? = null

                for (i in 0 until releasesArray.length()) {
                    val rel = releasesArray.getJSONObject(i)
                    if (rel.optBoolean("draft", false)) continue

                    val tagName = rel.optString("tag_name", rel.optString("name", ""))
                    val lowerTag = tagName.lowercase()
                    // Reject TV and Desktop releases
                    if (lowerTag.startsWith("tv-") || lowerTag.startsWith("tv_") ||
                        lowerTag.startsWith("desktop-") || lowerTag.startsWith("desktop_")) {
                        continue
                    }
                    val cleanTag = MobileVersionComparator.cleanVersion(tagName)

                    val assetsJson = rel.optJSONArray("assets") ?: JSONArray()
                    val allAssets = mutableListOf<MobileReleaseAsset>()
                    for (j in 0 until assetsJson.length()) {
                        val a = assetsJson.getJSONObject(j)
                        allAssets.add(
                            MobileReleaseAsset(
                                name = a.optString("name", ""),
                                downloadUrl = a.optString("browser_download_url", ""),
                                sizeBytes = a.optLong("size", 0L)
                            )
                        )
                    }

                    val targetApk = MobileUpdateConfig.selectTargetApkAsset(allAssets)
                    if (targetApk != null && MobileVersionComparator.isNewer(cleanTag, currentVersion)) {
                        if (bestCandidateVersion.isBlank() || MobileVersionComparator.isNewer(cleanTag, bestCandidateVersion)) {
                            bestCandidateVersion = cleanTag
                            bestCandidateRelease = rel
                            bestTargetAsset = targetApk
                            bestChecksumAsset = allAssets.firstOrNull {
                                it.name.contains("sha256", ignoreCase = true) || it.name.endsWith(".sha256")
                            }
                        }
                    }
                }

                if (bestCandidateRelease != null && bestTargetAsset != null) {
                    val isAvailable = MobileVersionComparator.isNewer(bestCandidateVersion, currentVersion)
                    return@withContext MobileUpdateInfo(
                        isUpdateAvailable = isAvailable,
                        currentVersion = currentVersion,
                        latestVersion = bestCandidateVersion,
                        releaseTitle = bestCandidateRelease.optString("name", "SkyDrop Android v$bestCandidateVersion"),
                        releaseNotes = bestCandidateRelease.optString("body", "Bug fixes and stability improvements."),
                        publishedAt = bestCandidateRelease.optString("published_at", ""),
                        targetAsset = bestTargetAsset,
                        checksumAsset = bestChecksumAsset,
                        releaseHtmlUrl = bestCandidateRelease.optString("html_url", MobileUpdateConfig.RELEASES_REPO_URL)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "API releases check failed (${e.message}), attempting Atom feed fallback...")
        }

        return@withContext checkReleasesViaAtomFeed(currentVersion)
    }

    private suspend fun checkReleasesViaAtomFeed(currentVersion: String): MobileUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(MobileUpdateConfig.ATOM_RELEASES_URL)
                .header("User-Agent", MobileUpdateConfig.USER_AGENT)
                .build()

            val res = client.newCall(req).execute()
            if (!res.isSuccessful) {
                return@withContext emptyUpdateInfo(currentVersion)
            }

            val body = res.body?.string().orEmpty()
            val entryRegex = Regex("<entry>([\\s\\S]*?)</entry>")
            val titleRegex = Regex("<title>([^<]+)</title>")
            val linkRegex = Regex("<link[^>]+href=\"([^\"]+)\"")
            val contentRegex = Regex("<content[^>]*>([\\s\\S]*?)</content>")

            val entries = entryRegex.findAll(body)
            for (entryMatch in entries) {
                val entryXml = entryMatch.groupValues[1]
                val title = titleRegex.find(entryXml)?.groupValues?.get(1)?.trim().orEmpty()
                val link = linkRegex.find(entryXml)?.groupValues?.get(1)?.trim().orEmpty()
                val content = contentRegex.find(entryXml)?.groupValues?.get(1)?.trim().orEmpty()

                val rawTag = link.substringAfterLast("/")
                val lowerTag = rawTag.lowercase()
                if (lowerTag.startsWith("tv-") || lowerTag.startsWith("tv_") ||
                    lowerTag.startsWith("desktop-") || lowerTag.startsWith("desktop_")) {
                    continue
                }

                val cleanTag = MobileVersionComparator.cleanVersion(rawTag)

                if (MobileVersionComparator.isNewer(cleanTag, currentVersion)) {
                    val assets = fetchAssetsFromExpandedHtml(rawTag)
                    val targetApk = MobileUpdateConfig.selectTargetApkAsset(assets)
                    if (targetApk != null) {
                        val cleanNotes = content.replace(Regex("<[^>]*>"), " ").trim()
                        return@withContext MobileUpdateInfo(
                            isUpdateAvailable = true,
                            currentVersion = currentVersion,
                            latestVersion = cleanTag,
                            releaseTitle = title.ifBlank { "SkyDrop Android v$cleanTag" },
                            releaseNotes = cleanNotes.ifBlank { "Bug fixes and stability enhancements." },
                            publishedAt = "",
                            targetAsset = targetApk,
                            checksumAsset = assets.firstOrNull { it.name.contains("sha256", ignoreCase = true) },
                            releaseHtmlUrl = link.ifBlank { MobileUpdateConfig.RELEASES_REPO_URL }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Atom feed fallback", e)
        }

        return@withContext emptyUpdateInfo(currentVersion)
    }

    private fun fetchAssetsFromExpandedHtml(tag: String): List<MobileReleaseAsset> {
        val list = mutableListOf<MobileReleaseAsset>()
        try {
            val url = "${MobileUpdateConfig.EXPANDED_ASSETS_URL}/$tag"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MobileUpdateConfig.USER_AGENT)
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return list

            val html = res.body?.string().orEmpty()
            val assetRegex = Regex("href=\"(/[^\"/]+/[^\"/]+/releases/download/[^\"]+)\"")
            for (m in assetRegex.findAll(html)) {
                val relUrl = m.groupValues[1]
                val name = relUrl.substringAfterLast("/")
                val fullUrl = "https://github.com$relUrl"
                list.add(MobileReleaseAsset(name = name, downloadUrl = fullUrl, sizeBytes = 0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing expanded assets for $tag", e)
        }
        return list
    }

    suspend fun downloadApk(
        asset: MobileReleaseAsset,
        onProgress: (MobileUpdateDownloadProgress) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val destFile = File(updatesDir, asset.name.ifBlank { "SkyDrop-android-update.apk" })
        if (destFile.exists()) destFile.delete()

        try {
            val req = Request.Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", MobileUpdateConfig.USER_AGENT)
                .build()

            val res = client.newCall(req).execute()
            if (!res.isSuccessful) {
                onProgress(
                    MobileUpdateDownloadProgress(
                        bytesDownloaded = 0,
                        totalBytes = 0,
                        percent = 0,
                        error = "Server error ${res.code}"
                    )
                )
                return@withContext null
            }

            val body = res.body ?: run {
                onProgress(MobileUpdateDownloadProgress(0, 0, 0, error = "Empty response"))
                return@withContext null
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var lastNotified = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastNotified > 100 || downloadedBytes == totalBytes) {
                            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            onProgress(
                                MobileUpdateDownloadProgress(
                                    bytesDownloaded = downloadedBytes,
                                    totalBytes = totalBytes,
                                    percent = percent.coerceIn(0, 100)
                                )
                            )
                            lastNotified = now
                        }
                    }
                    output.flush()
                }
            }

            onProgress(
                MobileUpdateDownloadProgress(
                    bytesDownloaded = downloadedBytes,
                    totalBytes = downloadedBytes,
                    percent = 100,
                    isComplete = true
                )
            )
            return@withContext destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            onProgress(
                MobileUpdateDownloadProgress(
                    bytesDownloaded = 0,
                    totalBytes = 0,
                    percent = 0,
                    error = e.localizedMessage ?: "Network error during download"
                )
            )
            return@withContext null
        }
    }

    suspend fun verifySha256(apkFile: File, expectedHash: String): Boolean = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || expectedHash.isBlank()) return@withContext true
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val computedHex = hashBytes.joinToString("") { "%02x".format(it) }
            val cleanExpected = expectedHash.trim().lowercase()
            return@withContext computedHex.equals(cleanExpected, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum computation failed", e)
            return@withContext false
        }
    }

    suspend fun fetchExpectedSha256(checksumAsset: MobileReleaseAsset?, apkName: String): String = withContext(Dispatchers.IO) {
        if (checksumAsset == null || checksumAsset.downloadUrl.isBlank()) return@withContext ""
        try {
            val req = Request.Builder()
                .url(checksumAsset.downloadUrl)
                .header("User-Agent", MobileUpdateConfig.USER_AGENT)
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return@withContext ""
            val content = res.body?.string().orEmpty()
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.endsWith(apkName, ignoreCase = true)) {
                    val hash = trimmed.substringBefore(" ").trim()
                    if (hash.length == 64) return@withContext hash
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch sha256 checksum", e)
        }
        return@withContext ""
    }

    fun promptInstallApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun emptyUpdateInfo(currentVersion: String): MobileUpdateInfo {
        return MobileUpdateInfo(
            isUpdateAvailable = false,
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            releaseTitle = "SkyDrop Android is up to date",
            releaseNotes = "",
            publishedAt = "",
            targetAsset = null,
            checksumAsset = null,
            releaseHtmlUrl = MobileUpdateConfig.RELEASES_REPO_URL
        )
    }
}
