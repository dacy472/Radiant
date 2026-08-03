package com.radiant.webtoon.core.source

import com.radiant.webtoon.core.model.*

class MockSource : Source {
    override val id = "mock-source"
    override val name = "Mock Source"
    override val baseUrl = "https://example.com"
    override val lang = "en"
    override var isPinned: Boolean = false
    override val icon = "🎨"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSearch = true,
        supportsLatest = true,
        supportsNsfw = false,
        supportsFilters = false
    )

    override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse {
        return PagedWebtoonResponse(
            webtoons = List(10) { i ->
                Webtoon(
                    id = "mock-$i",
                    title = "Mock Webtoon $i",
                    thumbnail = "https://picsum.photos/seed/$i/200/300",
                    status = "Ongoing",
                    author = "Mock Author"
                )
            },
            hasNextPage = true
        )
    }

    override suspend fun fetchLatestManga(page: Int): PagedWebtoonResponse {
        return PagedWebtoonResponse(
            webtoons = List(10) { i ->
                Webtoon(
                    id = "latest-$i",
                    title = "Latest Mock $i",
                    thumbnail = "https://picsum.photos/seed/latest$i/200/300",
                    status = "Ongoing",
                    author = "Mock Author"
                )
            },
            hasNextPage = page < 3
        )
    }

    override suspend fun fetchSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): eu.kanade.tachiyomi.source.model.MangasPage {
        return eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon {
        val details = getMangaDetails(webtoonId)
        return details.copy(chapters = getChapterList(webtoonId))
    }

    override suspend fun getMangaDetails(webtoonId: String): Webtoon {
        return Webtoon(
            id = webtoonId,
            title = "Mock Webtoon $webtoonId",
            thumbnail = "https://picsum.photos/seed/$webtoonId/200/300",
            status = "Ongoing",
            author = "Mock Author",
            description = "This is a mock description for $webtoonId."
        )
    }

    override suspend fun getChapterList(webtoonId: String): List<Chapter> {
        return List(5) { i ->
            Chapter(
                id = "ch-$i",
                title = "Chapter $i",
                number = i.toDouble()
            )
        }
    }

    override suspend fun getChapterPages(chapterId: String): List<String> {
        return List(3) { i -> "https://picsum.photos/seed/$chapterId-$i/400/600" }
    }
}
