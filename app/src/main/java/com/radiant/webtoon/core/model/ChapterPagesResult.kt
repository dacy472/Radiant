package com.radiant.webtoon.core.model

/**
 * Result of fetching a chapter's page list, including HTTP metadata for reader error UI.
 */
data class ChapterPagesResult(
    val pages: List<String>,
    val httpStatus: Int? = null,
    val chapterUrl: String? = null,
    val cloudflareChallenge: Boolean = false,
) {
    fun isCloudflareBlocked(): Boolean {
        if (pages.isNotEmpty()) return false
        if (cloudflareChallenge) return true
        return httpStatus in CLOUDFLARE_BLOCKED_STATUS_CODES
    }

    companion object {
        val CLOUDFLARE_BLOCKED_STATUS_CODES = setOf(403, 503)
    }
}
