package com.famage.skydrop.updater

import org.junit.Assert.*
import org.junit.Test

class MobileUpdateAssetSelectionTest {

    @Test
    fun testVersionComparator() {
        assertTrue(MobileVersionComparator.isNewer("1.0.46", "1.0.45"))
        assertTrue(MobileVersionComparator.isNewer("1.1.0", "1.0.45"))
        assertTrue(MobileVersionComparator.isNewer("2.0.0", "1.9.9"))
        assertFalse(MobileVersionComparator.isNewer("1.0.45", "1.0.45"))
        assertFalse(MobileVersionComparator.isNewer("1.0.44", "1.0.45"))

        assertEquals("1.0.45", MobileVersionComparator.cleanVersion("android-v1.0.45"))
        assertEquals("1.0.45", MobileVersionComparator.cleanVersion("mobile-v1.0.45"))
        assertEquals("1.0.45", MobileVersionComparator.cleanVersion("v1.0.45"))
        assertEquals("1.0.45", MobileVersionComparator.cleanVersion("1.0.45"))
    }

    @Test
    fun testSelectTargetApkAsset_PrefersAndroidApkAndExcludesDesktopAndTv() {
        val assets = listOf(
            MobileReleaseAsset("skydrop-1.0.45-linux-amd64.deb", "https://example.com/deb", 1000L),
            MobileReleaseAsset("skydrop-1.0.45-win-x64-setup.exe", "https://example.com/exe", 2000L),
            MobileReleaseAsset("SkyDrop-tv-1.0.45.apk", "https://example.com/tv-apk", 3000L),
            MobileReleaseAsset("SkyDrop-android-1.0.45.apk", "https://example.com/android-apk", 4000L),
            MobileReleaseAsset("sha256sums.txt", "https://example.com/sha", 100L)
        )

        val selected = MobileUpdateConfig.selectTargetApkAsset(assets)
        assertNotNull(selected)
        assertEquals("SkyDrop-android-1.0.45.apk", selected?.name)
        assertEquals("https://example.com/android-apk", selected?.downloadUrl)
    }

    @Test
    fun testSelectTargetApkAsset_RejectsDesktopAndTvOnly() {
        val assets = listOf(
            MobileReleaseAsset("skydrop-1.0.45-linux-amd64.deb", "https://example.com/deb", 1000L),
            MobileReleaseAsset("SkyDrop-tv-1.0.45.apk", "https://example.com/tv-apk", 3000L)
        )

        val selected = MobileUpdateConfig.selectTargetApkAsset(assets)
        assertNull(selected)
    }
}
