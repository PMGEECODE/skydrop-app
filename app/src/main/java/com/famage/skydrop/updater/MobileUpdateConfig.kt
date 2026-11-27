package com.famage.skydrop.updater

/**
 * Central configuration for SkyDrop Mobile GitHub Releases updater.
 */
object MobileUpdateConfig {
    const val DEFAULT_REPO_OWNER = "PMGEECODE"
    const val DEFAULT_REPO_NAME = "skydrop-release"

    const val RELEASES_REPO_URL = "https://github.com/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME"
    const val API_RELEASES_URL = "https://api.github.com/repos/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME/releases"
    const val ATOM_RELEASES_URL = "https://github.com/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME/releases.atom"
    const val EXPANDED_ASSETS_URL = "https://github.com/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME/releases/expanded_assets"

    const val USER_AGENT = "SkyDrop-Android-App"

    // TV / Desktop keywords that must NOT be selected for Android Mobile
    val EXCLUDED_KEYWORDS = listOf("tv", "leanback", "android-tv", "desktop")
    val EXCLUDED_EXTENSIONS = listOf(".exe", ".deb", ".appimage", ".tar.gz", ".zip", ".msi", ".dmg")

    // Preferred keywords for mobile APKs
    val MOBILE_KEYWORDS = listOf("android", "mobile")

    /**
     * Selects the correct APK asset for the Mobile app:
     * - Strictly rejects TV assets
     * - Strictly rejects desktop assets
     * - Prefers APKs containing "android" or "mobile" in the filename
     */
    fun selectTargetApkAsset(assets: List<MobileReleaseAsset>): MobileReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            val name = asset.name.lowercase()
            name.endsWith(".apk") &&
                EXCLUDED_EXTENSIONS.none { name.endsWith(it) } &&
                EXCLUDED_KEYWORDS.none { name.contains(it) }
        }

        if (apkAssets.isEmpty()) return null

        val mobileSpecific = apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            MOBILE_KEYWORDS.any { name.contains(it) }
        }

        return mobileSpecific ?: apkAssets.firstOrNull()
    }
}
