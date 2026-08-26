package com.radiant.webtoon.core.source

import android.util.Log
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import com.radiant.webtoon.core.debug.PageListDebugLogger
import com.radiant.webtoon.core.model.*
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.source.model.PageListFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.source.Source as TachiyomiSource
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * Wraps a Tachiyomi CatalogueSource/HttpSource and adapts it to our app's Source interface.
 * This bridges the gap between Tachiyomi's data models (SManga, SChapter, Page)
 * and our app's models (Webtoon, Chapter, List<String>).
 */
class TachiyomiSourceAdapter(
    val tachiyomiSource: TachiyomiSource,
    override val icon: String = "emoji:📦"
) : Source {

    private val tag = "TachiyomiAdapter[${tachiyomiSource.name}]"

    private val jsEngine: JavaScriptEngine by injectLazy()

    companion object {
        /**
         * Many extension sites only match search queries by exact substring, prefix,
         * or require ALL words to appear, so imprecise/partial multi-word queries
         * (e.g. "ghost game") often return zero results even when a matching title
         * exists. As a fallback we re-search the site using each individual word
         * (much better coverage than scanning Popular/Latest, since it hits the
         * site's real search index) and merge/rank the results.
         */
        private const val FALLBACK_MAX_TOKENS = 4
        private const val FALLBACK_MIN_TOKEN_LENGTH = 3
        private const val FALLBACK_ENOUGH_CANDIDATES = 15

        /** Bound on Popular/Latest pages scanned by the last-resort listing fallback. */
        private const val FALLBACK_MAX_PAGES_PER_LISTING = 2

        private val FALLBACK_STOPWORDS = setOf(
            "the", "and", "for", "with", "but", "that", "this", "from", "your",
            "have", "has", "was", "were", "are", "not", "you", "his", "her",
            "him", "she", "who", "how", "why", "what", "when", "where", "into",
        )
    }

    override val id: String = tachiyomiSource.id.toString()

    override val name: String = tachiyomiSource.name

    override val baseUrl: String = when (tachiyomiSource) {
        is HttpSource -> tachiyomiSource.baseUrl
        else -> ""
    }

    override val lang: String = when (tachiyomiSource) {
        is CatalogueSource -> tachiyomiSource.lang
        else -> "en"
    }

    override var isPinned: Boolean = false

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSearch = tachiyomiSource is CatalogueSource,
        supportsLatest = tachiyomiSource is CatalogueSource,
        supportsNsfw = false,
        supportsFilters = true
    )

    /** HTTP headers from the underlying HttpSource for image requests. */
    val httpHeaders: Headers
        get() = (tachiyomiSource as? HttpSource)?.headers ?: Headers.headersOf()

    override fun getMangaPageUrl(webtoonId: String): String {
        val manga = SManga.create().apply { url = webtoonId }
        val http = tachiyomiSource as? HttpSource
        return if (http != null) {
            try {
                http.getMangaUrl(manga)
            } catch (_: Exception) {
                super.getMangaPageUrl(webtoonId)
            }
        } else {
            super.getMangaPageUrl(webtoonId)
        }
    }

    override fun getWebViewHeaders(): Map<String, String> {
        return try {
            httpHeaders.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun getFilterList(): FilterList {
        return try {
            (tachiyomiSource as? CatalogueSource)?.getFilterList() ?: FilterList()
        } catch (_: Exception) {
            FilterList()
        }
    }

    override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse = withContext(Dispatchers.IO) {
        try {
            val catalogueSource = tachiyomiSource as? CatalogueSource
                ?: throw Exception("Source does not support catalogue browsing")

            val mangasPage = catalogueSource.getPopularManga(page)

            PagedWebtoonResponse(
                webtoons = mangasPage.mangas.map { it.toWebtoon() },
                hasNextPage = mangasPage.hasNextPage
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "Failed to get popular manga", e)
            throw friendlyError(e, "Failed to load popular titles")
        }
    }

    override suspend fun fetchLatestManga(page: Int): PagedWebtoonResponse = withContext(Dispatchers.IO) {
        try {
            val catalogueSource = tachiyomiSource as? CatalogueSource
                ?: return@withContext PagedWebtoonResponse(emptyList(), false)

            val mangasPage = catalogueSource.getLatestUpdates(page)

            PagedWebtoonResponse(
                webtoons = mangasPage.mangas.map { it.toWebtoon() },
                hasNextPage = mangasPage.hasNextPage
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "Failed to get latest manga", e)
            throw friendlyError(e, "Failed to load latest titles")
        }
    }

    override suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        try {
            val catalogueSource = tachiyomiSource as? CatalogueSource
                ?: throw Exception("Source does not support search")

            val directResult = try {
                catalogueSource.getSearchManga(page, query, filters)
            } catch (e: NoSuchElementException) {
                // Observable completed without emitting — treat as empty results
                MangasPage(emptyList(), false)
            }

            // Only fall back on the first page of a plain (unfiltered) query — the
            // site's own search backend often does exact/prefix-only matching, so
            // scan a bounded number of Popular/Latest pages and match by word instead.
            if (directResult.mangas.isNotEmpty() || page != 1 || query.isBlank() || filters.isNotEmpty()) {
                return@withContext directResult
            }

            val fallback = try {
                fallbackTokenSearch(catalogueSource, query)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(tag, "Fallback token search failed for query: $query", e)
                null
            }

            fallback ?: directResult
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "Failed to search manga for query: $query", e)
            throw friendlyError(e, "Search failed")
        }
    }

    /**
     * Client-side fallback used when the source's own (full-phrase) search returns zero
     * results. Tokenizes [query], drops noise/stopwords, then re-issues the SITE'S OWN
     * search for each individual token — this hits the site's real search index (far
     * better coverage than a small Popular/Latest scan), so a remembered word/phrase like
     * "i killed the dragon but" surfaces titles that contain just "dragon" or "killed".
     * Results are merged by URL and ranked by how many distinct tokens matched. If every
     * per-token search comes back empty, falls back further to a bounded Popular/Latest
     * scan with substring matching as a last resort.
     */
    private suspend fun fallbackTokenSearch(catalogueSource: CatalogueSource, query: String): MangasPage? {
        val tokens = query.lowercase(java.util.Locale.ROOT)
            .split(Regex("[^a-z0-9']+"))
            .map { it.removeSuffix("'s") }
            .filter { it.length >= FALLBACK_MIN_TOKEN_LENGTH && it !in FALLBACK_STOPWORDS }
            .distinct()
            .take(FALLBACK_MAX_TOKENS)
        if (tokens.isEmpty()) return null

        val hits = LinkedHashMap<String, Pair<SManga, Int>>()

        for (token in tokens) {
            if (hits.size >= FALLBACK_ENOUGH_CANDIDATES) break
            val tokenPage = try {
                catalogueSource.getSearchManga(1, token, FilterList())
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(tag, "Per-token fallback search failed for token '$token'", e)
                continue
            }
            tokenPage.mangas.forEach { manga ->
                val matchCount = (hits[manga.url]?.second ?: 0) + 1
                hits[manga.url] = manga to matchCount
            }
        }

        if (hits.isNotEmpty()) {
            val ranked = hits.values
                .sortedByDescending { (_, matchCount) -> matchCount }
                .map { (manga, _) -> manga }
            return MangasPage(ranked, false)
        }

        return fallbackListingScan(catalogueSource, tokens)
    }

    /**
     * Last-resort fallback for sources whose own search doesn't handle single-word queries
     * either. Scans a small, bounded number of Popular/Latest pages and matches titles
     * containing at least one token (case-insensitive substring match).
     */
    private suspend fun fallbackListingScan(catalogueSource: CatalogueSource, tokens: List<String>): MangasPage? {
        val candidates = LinkedHashMap<String, SManga>()

        suspend fun collectFrom(fetchPage: suspend (Int) -> MangasPage) {
            var listingPage = 1
            var hasNext = true
            while (hasNext && listingPage <= FALLBACK_MAX_PAGES_PER_LISTING) {
                val mangasPage = try {
                    fetchPage(listingPage)
                } catch (e: NoSuchElementException) {
                    break
                }
                mangasPage.mangas.forEach { manga -> candidates.putIfAbsent(manga.url, manga) }
                hasNext = mangasPage.hasNextPage
                listingPage++
            }
        }

        collectFrom { p -> catalogueSource.getPopularManga(p) }
        collectFrom { p -> catalogueSource.getLatestUpdates(p) }

        if (candidates.isEmpty()) return null

        val ranked = candidates.values
            .map { manga -> manga to tokens.count { token -> manga.title.contains(token, ignoreCase = true) } }
            .filter { (_, matchCount) -> matchCount > 0 }
            .sortedByDescending { (_, matchCount) -> matchCount }
            .map { (manga, _) -> manga }

        return ranked.takeIf { it.isNotEmpty() }?.let { MangasPage(it, false) }
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon = withContext(Dispatchers.IO) {
        try {
            val manga = SManga.create().apply {
                url = webtoonId
            }

            val update = try {
                lockedMangaUpdate(manga, fetchDetails = true, fetchChapters = true)
            } catch (e: Exception) {
                // Some sources fail to fetch chapters together with details; fall back to
                // details-only so the page still renders with an empty chapter list.
                Log.e(tag, "Failed to get manga update for $webtoonId, falling back to details only", e)
                val detailsOnly = lockedMangaUpdate(manga, fetchDetails = true, fetchChapters = false)
                eu.kanade.tachiyomi.source.model.SMangaUpdate(detailsOnly.manga, emptyList())
            }
            val detailedManga = update.manga
            val chapterList = update.chapters
            ChapterMemoCache.putManga(id, webtoonId, detailedManga.memo)
            if (detailedManga.url.isNotBlank() && detailedManga.url != webtoonId) {
                ChapterMemoCache.putManga(id, detailedManga.url, detailedManga.memo)
            }

            Webtoon(
                id = webtoonId,
                title = detailedManga.title.ifEmpty { "Unknown" },
                thumbnail = detailedManga.thumbnail_url ?: "",
                url = webtoonId,
                status = statusToString(detailedManga.status),
                author = detailedManga.author,
                description = detailedManga.description,
                genres = detailedManga.genre?.split(", ")?.map { it.trim() } ?: emptyList(),
                chapters = chapterList.mapIndexed { index, sChapter ->
                    toAppChapter(sChapter, index, chapterList.size)
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to get webtoon detail: $webtoonId", e)
            throw friendlyError(e, "Failed to load title details")
        }
    }

    override suspend fun getMangaDetails(webtoonId: String): Webtoon = withContext(Dispatchers.IO) {
        val manga = SManga.create().apply { url = webtoonId }
        val update = lockedMangaUpdate(manga, fetchDetails = true, fetchChapters = false)
        update.manga.toWebtoon().copy(id = webtoonId)
    }

    override suspend fun getChapterList(webtoonId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val manga = SManga.create().apply { url = webtoonId }
        val update = lockedMangaUpdate(manga, fetchDetails = false, fetchChapters = true)
        val chapterList = update.chapters
        chapterList.mapIndexed { index, sChapter ->
            toAppChapter(sChapter, index, chapterList.size)
        }
    }

    override suspend fun getChapterPages(chapterId: String): List<String> =
        getChapterPagesResult(chapterId, mangaId = null).pages

    override suspend fun getChapterPagesResult(chapterId: String, mangaId: String?): ChapterPagesResult =
        withContext(Dispatchers.IO) {
            val session = PageListDebugLogger.beginSession(
                sourceId = id,
                sourceName = name,
                chapterId = chapterId,
                chapterUrl = getChapterPageUrl(chapterId),
            )
            PageListDebugLogger.withSession(session) {
                try {
                    var chapter = buildSChapter(chapterId)
                    if (needsMemoRefresh(chapter, mangaId)) {
                        chapter = refreshChapterMemo(chapterId, mangaId!!) ?: chapter
                    }

                    // Flame Comics: native __NEXT_DATA__ scraper — bypasses the extension APK.
                    val httpSource = tachiyomiSource as? HttpSource
                    val flameMatch = httpSource != null && isFlameComicsSource()
                    if (flameMatch) {
                        val chapterUrl = httpSource!!.getChapterUrl(chapter)
                        PageListDebugLogger.logStep(
                            step = "flame_comics_native_scraper_start",
                            strategy = "FlameComicsScraper.fetchPageList",
                            extra = mapOf("url" to chapterUrl),
                        )
                        val scraped = FlameComicsScraper.from(httpSource, jsEngine)
                            .fetchPageList(chapterUrl)
                        if (scraped.isNotEmpty()) {
                            return@withSession ChapterPagesResult(
                                pages = scraped,
                                httpStatus = 200,
                                chapterUrl = chapterUrl,
                                cloudflareChallenge = false,
                            )
                        }
                        PageListDebugLogger.logEmptyPageList(
                            step = "flame_comics_native_scraper_empty",
                            strategy = "FlameComicsScraper.fetchPageList",
                            extra = mapOf("url" to chapterUrl),
                        )
                        return@withSession ChapterPagesResult(
                            pages = emptyList(),
                            httpStatus = null,
                            chapterUrl = chapterUrl,
                            cloudflareChallenge = false,
                        )
                    }

                    // Prefer suspend getPageList() — modern extensions (e.g. Asura Scans)
                    // override it and throw UnsupportedOperationException from pageListRequest.
                    var pages = tryGetPageList(chapter)
                    if (pages.isEmpty() && !mangaId.isNullOrBlank() && chapter.memo.isEmpty()) {
                        // Generic recovery for any source that needs chapter memo.
                        chapter = refreshChapterMemo(chapterId, mangaId) ?: chapter
                        if (chapter.memo.isNotEmpty()) {
                            pages = tryGetPageList(chapter)
                        }
                    } else if (pages.isEmpty() && needsMemoRefresh(chapter, mangaId)) {
                        chapter = refreshChapterMemo(chapterId, mangaId!!) ?: chapter
                        pages = tryGetPageList(chapter)
                    }

                    val fetch = if (httpSource == null) {
                        if (pages.isEmpty()) {
                            PageListDebugLogger.logEmptyPageList(
                                step = "empty_after_getPageList",
                                strategy = "non-HttpSource.getPageList",
                            )
                        }
                        PageListFetchResult(
                            pages = pages,
                            httpStatus = -1,
                            chapterUrl = getChapterPageUrl(chapterId),
                            cloudflareChallenge = false,
                        )
                    } else if (pages.isNotEmpty()) {
                        PageListFetchResult(
                            pages = pages,
                            httpStatus = -1,
                            chapterUrl = try {
                                httpSource.getChapterUrl(chapter)
                            } catch (_: Exception) {
                                getChapterPageUrl(chapterId)
                            },
                            cloudflareChallenge = false,
                        )
                    } else {
                        // Empty from getPageList — try legacy metadata path unless UOE.
                        try {
                            httpSource.fetchPageListWithMetadata(chapter)
                        } catch (_: UnsupportedOperationException) {
                            PageListFetchResult(
                                pages = emptyList(),
                                httpStatus = -1,
                                chapterUrl = try {
                                    httpSource.getChapterUrl(chapter)
                                } catch (_: Exception) {
                                    getChapterPageUrl(chapterId)
                                },
                                cloudflareChallenge = false,
                            )
                        }
                    }

                    val mappedPages = mapPageUrls(fetch.pages)

                    if (mappedPages.isEmpty() && fetch.pages.isNotEmpty()) {
                        PageListDebugLogger.logEmptyPageList(
                            step = "empty_after_mapPageUrls",
                            httpStatus = fetch.httpStatus,
                            strategy = "mapPageUrls",
                            extra = mapOf("rawPageCount" to fetch.pages.size.toString()),
                        )
                    } else if (mappedPages.isEmpty()) {
                        PageListDebugLogger.logEmptyPageList(
                            step = "empty_final",
                            httpStatus = fetch.httpStatus,
                            strategy = "getChapterPagesResult",
                            extra = mapOf(
                                "cloudflareChallenge" to fetch.cloudflareChallenge.toString(),
                            ),
                        )
                    }

                    ChapterPagesResult(
                        pages = mappedPages,
                        httpStatus = fetch.httpStatus.takeIf { it >= 0 },
                        chapterUrl = fetch.chapterUrl,
                        cloudflareChallenge = fetch.cloudflareChallenge,
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Failed to get chapter pages: $chapterId", e)
                    PageListDebugLogger.logEmptyPageList(
                        step = "getChapterPagesResult_exception",
                        error = e,
                        strategy = "getChapterPagesResult",
                    )
                    val isCloudflare = e.message?.contains("cloudflare", ignoreCase = true) == true
                    if (isCloudflare) {
                        ChapterPagesResult(
                            pages = emptyList(),
                            httpStatus = 403,
                            chapterUrl = getChapterPageUrl(chapterId),
                            cloudflareChallenge = true,
                        )
                    } else {
                        throw friendlyError(e, "Failed to load chapter pages")
                    }
                }
            }
        }

    private fun toAppChapter(sChapter: SChapter, index: Int, total: Int): Chapter {
        ChapterMemoCache.putChapter(id, sChapter.url, sChapter.memo)
        return Chapter(
            id = sChapter.url,
            title = sChapter.name,
            url = sChapter.url,
            date = com.radiant.webtoon.core.util.ChapterDates.formatExact(sChapter.date_upload),
            dateUploadMillis = sChapter.date_upload.takeIf { it > 0 } ?: 0L,
            number = if (sChapter.chapter_number >= 0) {
                sChapter.chapter_number.toDouble()
            } else {
                (total - index).toDouble()
            },
        )
    }

    private fun buildSChapter(chapterId: String): SChapter {
        return SChapter.create().apply {
            url = chapterId
            ChapterMemoCache.getChapter(id, chapterId)?.let { memo = it }
        }
    }

    private fun needsMemoRefresh(chapter: SChapter, mangaId: String?): Boolean {
        if (mangaId.isNullOrBlank()) return false
        if (chapter.memo.isNotEmpty()) return false
        // Asura / similar hosts require memo for page URLs.
        return name.contains("asura", ignoreCase = true) ||
            baseUrl.contains("asura", ignoreCase = true)
    }

    private suspend fun refreshChapterMemo(chapterId: String, mangaId: String): SChapter? {
        return try {
            val manga = SManga.create().apply {
                url = mangaId
                ChapterMemoCache.getManga(id, mangaId)?.let { memo = it }
            }
            val update = lockedMangaUpdate(manga, fetchDetails = true, fetchChapters = true)
            ChapterMemoCache.putManga(id, mangaId, update.manga.memo)
            update.chapters.forEach { ChapterMemoCache.putChapter(id, it.url, it.memo) }
            val match = update.chapters.firstOrNull { it.url == chapterId }
                ?: update.chapters.firstOrNull {
                    it.url.substringAfterLast('/') == chapterId.substringAfterLast('/')
                }
            match
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh chapter memo for $chapterId", e)
            null
        }
    }

    private suspend fun tryGetPageList(chapter: SChapter): List<eu.kanade.tachiyomi.source.model.Page> {
        return try {
            tachiyomiSource.getPageList(chapter)
        } catch (e: UnsupportedOperationException) {
            emptyList()
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure(
                step = "getPageList_exception",
                error = e,
            )
            emptyList()
        }
    }

    override fun getChapterPageUrl(chapterId: String): String {
        val chapter = buildSChapter(chapterId)
        val http = tachiyomiSource as? HttpSource
        return if (http != null) {
            try {
                http.getChapterUrl(chapter)
            } catch (_: Exception) {
                super.getChapterPageUrl(chapterId)
            }
        } else {
            super.getChapterPageUrl(chapterId)
        }
    }

    private fun isFlameComicsSource(): Boolean {
        return FlameComicsScraper.matchesSource(name = name, id = id, baseUrl = baseUrl)
    }

    private suspend fun mapPageUrls(pages: List<eu.kanade.tachiyomi.source.model.Page>): List<String> {
        return pages.mapNotNull { page ->
            when {
                !page.imageUrl.isNullOrEmpty() -> page.imageUrl
                page.url.isNotEmpty() && tachiyomiSource is HttpSource -> {
                    try {
                        (tachiyomiSource as HttpSource).getImageUrl(page)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to resolve image URL for page ${page.index}", e)
                        null
                    }
                }
                else -> null
            }
        }
    }

    private suspend fun lockedMangaUpdate(
        manga: SManga,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): eu.kanade.tachiyomi.source.model.SMangaUpdate =
        MangaUpdateLocks.coalesce(
            sourceId = tachiyomiSource.id,
            mangaUrl = manga.url,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
        ) {
            tachiyomiSource.getMangaUpdate(manga, emptyList(), fetchDetails, fetchChapters)
        }

    private fun friendlyError(e: Throwable, fallback: String): Exception {
        val raw = e.message.orEmpty()
        val msg = when {
            e is NoClassDefFoundError || e is NoSuchMethodError || e is AbstractMethodError -> {
                // Include the raw JVM message (usually names the exact missing method/class) so
                // any remaining host/extension ABI gap is actionable from the UI alone.
                val detail = raw.takeIf { it.isNotBlank() }
                "This extension needs a newer app runtime (${e.javaClass.simpleName}${if (detail != null) ": $detail" else ""})."
            }
            raw.contains("Cloudflare", ignoreCase = true) ||
                raw.contains("403") ||
                raw.contains("cf_clearance", ignoreCase = true) ->
                "Blocked by Cloudflare. Open the site in WebView to clear the challenge, then retry."
            e is java.net.SocketTimeoutException || raw.contains("timeout", ignoreCase = true) ->
                "Connection timed out. Check your network and retry."
            raw.contains("Connection reset", ignoreCase = true) ||
                raw.contains("ERR_CONNECTION_RESET", ignoreCase = true) ||
                e is java.net.SocketException ->
                "Connection was reset. The site may be blocking this device or emulator."
            e is java.net.UnknownHostException ->
                "No internet connection or DNS lookup failed."
            raw.contains("Unable to resolve host", ignoreCase = true) ->
                "No internet connection or DNS lookup failed."
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
        return Exception(msg, e)
    }

    private fun SManga.toWebtoon(): Webtoon {
        return Webtoon(
            id = url,
            title = title,
            thumbnail = thumbnail_url ?: "",
            url = url,
            status = statusToString(status),
            author = author,
            description = description,
            genres = genre?.split(", ")?.map { it.trim() } ?: emptyList()
        )
    }

    private fun statusToString(status: Int): String {
        return when (status) {
            SManga.ONGOING -> "Ongoing"
            SManga.COMPLETED -> "Completed"
            SManga.LICENSED -> "Licensed"
            SManga.PUBLISHING_FINISHED -> "Publishing Finished"
            SManga.CANCELLED -> "Cancelled"
            SManga.ON_HIATUS -> "On Hiatus"
            else -> "Unknown"
        }
    }
}
