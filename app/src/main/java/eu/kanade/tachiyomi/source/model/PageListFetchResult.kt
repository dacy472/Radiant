package eu.kanade.tachiyomi.source.model

/**
 * Page list fetch outcome with HTTP metadata for Cloudflare-aware reader UI.
 */
data class PageListFetchResult(
    val pages: List<Page>,
    val httpStatus: Int,
    val chapterUrl: String,
    val cloudflareChallenge: Boolean,
)
