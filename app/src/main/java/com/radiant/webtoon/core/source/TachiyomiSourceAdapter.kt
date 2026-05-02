package com.radiant.webtoon.core.source

import android.util.Log
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import com.radiant.webtoon.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.kanade.tachiyomi.source.Source as TachiyomiSource

/**
 * Wraps a Tachiyomi CatalogueSource/HttpSource and adapts it to our app's Source interface.
 * This bridges the gap between Tachiyomi's data models (SManga, SChapter, Page)
 * and our app's models (Webtoon, Chapter, List<String>).
 */
class TachiyomiSourceAdapter(
    private val tachiyomiSource: TachiyomiSource,
    override val icon: String = "emoji:📦"
) : Source {

    private val tag = "TachiyomiAdapter[${tachiyomiSource.name}]"

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

    // Icon is now provided in constructor

    override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse = withContext(Dispatchers.IO) {
        try {
            val catalogueSource = tachiyomiSource as? CatalogueSource
                ?: throw Exception("Source does not support catalogue browsing")

            val mangasPage = catalogueSource.fetchPopularManga(page).toBlocking().first()

            PagedWebtoonResponse(
                webtoons = mangasPage.mangas.map { it.toWebtoon() },
                hasNextPage = mangasPage.hasNextPage
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to get popular manga", e)
            PagedWebtoonResponse(webtoons = emptyList(), hasNextPage = false)
        }
    }

    override suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        try {
            val catalogueSource = tachiyomiSource as? CatalogueSource
                ?: throw Exception("Source does not support search")

            try {
                catalogueSource.fetchSearchManga(page, query, filters)
                    .toBlocking()
                    .first()
            } catch (e: NoSuchElementException) {
                // If the Observable completes without emitting, return an empty page
                MangasPage(emptyList(), false)
            }
        } catch (e: Exception) {
            val errorDetails = when {
                e.message?.contains("403") == true -> "HTTP 403 (Cloudflare/Banned)"
                e.message?.contains("404") == true -> "HTTP 404 (Not Found)"
                e is java.net.SocketTimeoutException -> "Timeout during fetching"
                e is com.google.gson.JsonSyntaxException -> "JSON Parsing Error"
                else -> e.localizedMessage ?: "Unknown error"
            }
            Log.e(tag, "Failed to search manga for query: $query. Error: $errorDetails", e)
            MangasPage(emptyList(), false)
        }
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon = withContext(Dispatchers.IO) {
        try {
            // webtoonId is the manga URL
            val manga = SManga.create().apply {
                url = webtoonId
            }

            // Fetch details
            val detailedManga = tachiyomiSource.getMangaDetails(manga)

            // Fetch chapters
            val chapterList = try {
                tachiyomiSource.getChapterList(manga)
            } catch (e: Exception) {
                Log.e(tag, "Failed to get chapter list for $webtoonId", e)
                emptyList()
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
                    Chapter(
                        id = sChapter.url,
                        title = sChapter.name,
                        url = sChapter.url,
                        date = if (sChapter.date_upload > 0) {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                .format(java.util.Date(sChapter.date_upload))
                        } else null,
                        number = if (sChapter.chapter_number >= 0) sChapter.chapter_number.toDouble() else (chapterList.size - index).toDouble()
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to get webtoon detail: $webtoonId", e)
            Webtoon(id = webtoonId, title = "Error loading", thumbnail = "", url = webtoonId)
        }
    }

    override suspend fun getMangaDetails(webtoonId: String): Webtoon = withContext(Dispatchers.IO) {
        val manga = SManga.create().apply { url = webtoonId }
        val detailedManga = tachiyomiSource.getMangaDetails(manga)
        detailedManga.toWebtoon().copy(id = webtoonId)
    }

    override suspend fun getChapterList(webtoonId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val manga = SManga.create().apply { url = webtoonId }
        val chapterList = tachiyomiSource.getChapterList(manga)
        chapterList.mapIndexed { index, sChapter ->
            Chapter(
                id = sChapter.url,
                title = sChapter.name,
                url = sChapter.url,
                date = if (sChapter.date_upload > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(sChapter.date_upload))
                } else null,
                number = if (sChapter.chapter_number >= 0) sChapter.chapter_number.toDouble() else (chapterList.size - index).toDouble()
            )
        }
    }

    override suspend fun getChapterPages(chapterId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val chapter = eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                url = chapterId
            }

            val pages = tachiyomiSource.getPageList(chapter)

            // For each page, resolve the image URL
            pages.mapNotNull { page ->
                when {
                    // If imageUrl is already set, use it directly
                    !page.imageUrl.isNullOrEmpty() -> page.imageUrl
                    // If page.url is set, we may need to fetch the image URL
                    page.url.isNotEmpty() && tachiyomiSource is HttpSource -> {
                        try {
                            tachiyomiSource.fetchImageUrl(page).toBlocking().first()
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to resolve image URL for page ${page.index}", e)
                            null
                        }
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get chapter pages: $chapterId", e)
            emptyList()
        }
    }

    // ─── Conversion Helpers ───

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
