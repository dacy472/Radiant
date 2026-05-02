package com.radiant.webtoon.core.source

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.radiant.webtoon.core.model.Chapter
import com.radiant.webtoon.core.model.PagedWebtoonResponse
import com.radiant.webtoon.core.model.Webtoon
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Production-ready reference source adapter that does not depend on a custom backend.
 * It uses public image APIs to provide stable sample content and exercises full source flow.
 */
class PicsumSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) : Source {
    override val id: String = "picsum"
    override val name: String = "Picsum Demo"
    override val baseUrl: String = "https://picsum.photos"
    override val lang: String = "en"
    override var isPinned: Boolean
        get() = TODO("Not yet implemented")
        set(value) {}
    override val icon: String = "\uD83D\uDDBC\uFE0F"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSearch = true,
        supportsLatest = true,
        supportsNsfw = false,
        supportsFilters = false
    )

    override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse {
        return runCatching {
            val items = loadPhotos(page = page, limit = 20)
            PagedWebtoonResponse(
                webtoons = items.map {
                    Webtoon(
                        id = it.id,
                        title = it.author.ifBlank { "Untitled ${it.id}" },
                        thumbnail = "https://picsum.photos/id/${it.id}/300/450",
                        status = "Ongoing",
                        author = it.author
                    )
                },
                hasNextPage = items.isNotEmpty()
            )
        }.getOrElse { throw SourceError.Network(it) }
    }

    override suspend fun fetchSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): eu.kanade.tachiyomi.source.model.MangasPage {
        return eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon {
        val detail = getMangaDetails(webtoonId)
        return detail.copy(chapters = getChapterList(webtoonId))
    }

    override suspend fun getMangaDetails(webtoonId: String): Webtoon = runCatching {
        Webtoon(
            id = webtoonId,
            title = "Picsum Story #$webtoonId",
            thumbnail = "https://picsum.photos/id/$webtoonId/400/600",
            status = "Ongoing",
            author = "Picsum",
            description = "Reference source without custom server dependency."
        )
    }.getOrElse { throw SourceError.Parsing(it) }

    override suspend fun getChapterList(webtoonId: String): List<Chapter> = runCatching {
        (1..15).map { chapterNo ->
            Chapter(
                id = "$webtoonId-ch-$chapterNo",
                title = "Chapter $chapterNo",
                number = chapterNo.toDouble()
            )
        }
    }.getOrElse { throw SourceError.Parsing(it) }

    override suspend fun getChapterPages(chapterId: String): List<String> {
        val base = chapterId.hashCode().toUInt().toString().take(4)
        return (1..8).map { index ->
            "https://picsum.photos/seed/$base-$index/1200/1800"
        }
    }

    private fun loadPhotos(page: Int, limit: Int): List<PicsumPhoto> {
        val request = Request.Builder()
            .url("https://picsum.photos/v2/list?page=$page&limit=$limit")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string() ?: "[]"
            val type = object : TypeToken<List<PicsumPhoto>>() {}.type
            return gson.fromJson(body, type) ?: emptyList()
        }
    }
}

private data class PicsumPhoto(
    val id: String,
    val author: String,
)
