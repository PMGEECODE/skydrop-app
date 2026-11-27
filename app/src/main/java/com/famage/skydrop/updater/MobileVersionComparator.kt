package com.famage.skydrop.updater

/**
 * Robust semantic version parser and comparator for SkyDrop Mobile.
 */
object MobileVersionComparator {

    /**
     * Cleans release tags (e.g. "android-v1.0.45", "v1.0.45", "1.0.45") into standard semver strings ("1.0.45").
     */
    fun cleanVersion(tag: String): String {
        return tag.trim()
            .replace(Regex("^(android|mobile)[-_]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^v", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Compares two semantic version strings.
     * Returns > 0 if v1 > v2, < 0 if v1 < v2, and 0 if equal.
     */
    fun compare(v1: String, v2: String): Int {
        val clean1 = cleanVersion(v1)
        val clean2 = cleanVersion(v2)

        val parts1 = clean1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    /**
     * Returns true if targetVersion is strictly newer than installedVersion.
     */
    fun isNewer(targetVersion: String, installedVersion: String): Boolean {
        return compare(targetVersion, installedVersion) > 0
    }
}
