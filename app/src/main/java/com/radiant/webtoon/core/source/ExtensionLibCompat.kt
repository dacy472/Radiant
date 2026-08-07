package com.radiant.webtoon.core.source

/**
 * TachiyomiX / extension-lib version compatibility between host and extension APK.
 */
enum class ExtensionLibCompatibility {
    Compatible,
    TooNew,
    TooOld,
    Unknown,
}

object ExtensionLibCompat {
    const val SUPPORTED_VERSION = "1.6"

    fun parseVersion(metadata: android.os.Bundle): String? {
        // Mihon #3545: never call getString — Android may store numeric meta-data as Float/Int
        for (key in listOf("tachiyomix.extensionLib", "tachiyomi.extensionLib")) {
            metadata.keySet()
                .firstOrNull { it.equals(key, ignoreCase = true) }
                ?.let { k -> metadata.get(k)?.toString()?.trim() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    fun check(requiredVersion: String?): ExtensionLibCompatibility {
        if (requiredVersion.isNullOrBlank()) return ExtensionLibCompatibility.Compatible
        val required = parseSemVer(requiredVersion) ?: return ExtensionLibCompatibility.Unknown
        val supported = parseSemVer(SUPPORTED_VERSION) ?: return ExtensionLibCompatibility.Compatible
        return when {
            required.major > supported.major -> ExtensionLibCompatibility.TooNew
            required.major < supported.major -> ExtensionLibCompatibility.TooOld
            required.minor > supported.minor + 1 -> ExtensionLibCompatibility.TooNew
            else -> ExtensionLibCompatibility.Compatible
        }
    }

    private data class SemVer(val major: Int, val minor: Int)

    private fun parseSemVer(raw: String): SemVer? {
        val parts = raw.trim().split('.', '-', '_')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return SemVer(major, minor)
    }
}
