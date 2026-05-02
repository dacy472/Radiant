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
    suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage
    
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
