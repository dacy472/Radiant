package com.radiant.webtoon.core.source

/**
 * Curated extension IDs — only exact matches (or package suffixes) are considered
 * for update checks. Mirrors [extension-sdk] `VERIFIED_EXTENSIONS`.
 */
object VerifiedExtensions {
    val VERIFIED_EXTENSIONS: Set<String> = setOf(
        "en.mock",
        "en.picsum",
        "en.mangadex",
    )

    fun isVerified(extensionId: String): Boolean {
        val normalized = extensionId.lowercase().trim()
        if (normalized.isEmpty()) return false
        return VERIFIED_EXTENSIONS.any { verified ->
            normalized == verified || normalized.endsWith(".$verified")
        }
    }
}
