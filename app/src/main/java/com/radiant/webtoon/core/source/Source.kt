package com.radiant.webtoon.core.source

import com.radiant.webtoon.core.model.*
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage

/**
 * Base interface for all webtoon sources (extensions rewritten in Kotlin).
 */
interface Source {
    val id: String
    val name: String
    val baseUrl: String
    val lang: String
    var isPinned: Boolean
    /**
     * Icon for the source. Can be:
     * - "pkg:<package_name>" for Android app icons.
     * - "emoji:<emoji>" for simple text icons.
     * - "https://..." for remote icons.
     */
    val icon: String

    suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse
    suspend fun fetchLatestManga(page: Int): PagedWebtoonResponse = PagedWebtoonResponse(emptyList(), false)
    suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /** Tachiyomi-compatible filter list for search/browse. */
    fun getFilterList(): FilterList = FilterList()
    
    /**
     * Legacy method that fetches both details and chapters sequentially.
     */
    suspend fun getWebtoonDetail(webtoonId: String): Webtoon
    
    /**
     * Concurrent-friendly: Fetch only manga details.
     */
    suspend fun getMangaDetails(webtoonId: String): Webtoon

    /**
     * Concurrent-friendly: Fetch only chapter list.
     */
    suspend fun getChapterList(webtoonId: String): List<Chapter>

    suspend fun getChapterPages(chapterId: String): List<String>

    /**
     * Fetches chapter pages with HTTP metadata for reader error handling.
     */
    suspend fun getChapterPagesResult(chapterId: String): com.radiant.webtoon.core.model.ChapterPagesResult {
        val pages = getChapterPages(chapterId)
        return com.radiant.webtoon.core.model.ChapterPagesResult(pages = pages)
    }

    /**
     * Absolute URL for the chapter page on the source site (for WebView / Cloudflare bypass).
     */
    fun getChapterPageUrl(chapterId: String): String {
        if (chapterId.startsWith("http://") || chapterId.startsWith("https://")) return chapterId
        val base = baseUrl.trimEnd('/')
        val path = chapterId.trimStart('/')
        return if (base.isEmpty()) path else "$base/$path"
    }

    /**
     * Absolute URL for the manga/webtoon page on the source site (for WebView / open in browser).
     */
    fun getMangaPageUrl(webtoonId: String): String {
        if (webtoonId.startsWith("http://") || webtoonId.startsWith("https://")) return webtoonId
        val base = baseUrl.trimEnd('/')
        val path = webtoonId.trimStart('/')
        return if (base.isEmpty()) path else "$base/$path"
    }

    /** Optional HTTP headers for WebView loads (e.g. User-Agent from HttpSource). */
    fun getWebViewHeaders(): Map<String, String> = emptyMap()
    
    val capabilities: SourceCapabilities
}

sealed class SourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : SourceError("Network error: ${cause.message}", cause)
    class Parsing(cause: Throwable) : SourceError("Parsing error: ${cause.message}", cause)
    class Captcha(val url: String) : SourceError("Captcha required")
    class Unidentified : SourceError("Unknown error occurred")
}

data class SourceCapabilities(
    val supportsSearch: Boolean = false,
    val supportsLatest: Boolean = false,
    val supportsNsfw: Boolean = false,
    val supportsFilters: Boolean = false
)
