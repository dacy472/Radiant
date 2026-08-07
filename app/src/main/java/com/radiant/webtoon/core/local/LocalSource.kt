package com.radiant.webtoon.core.local

import com.radiant.webtoon.core.model.Chapter
import com.radiant.webtoon.core.model.PagedWebtoonResponse
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.source.Source
import com.radiant.webtoon.core.source.SourceCapabilities
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage

class LocalSource(
    private val catalog: LocalLibraryCatalog,
) : Source {
    override val id: String = LocalSourceIds.LOCAL_SOURCE_ID
    override val name: String = LocalSourceIds.LOCAL_SOURCE_NAME
    override val baseUrl: String = ""
    override val lang: String = "en"
    override var isPinned: Boolean = true
    override val icon: String = "📁"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSearch = true,
        supportsLatest = false,
        supportsNsfw = false,
        supportsFilters = false,
    )

    override suspend fun fetchPopularManga(page: Int): PagedWebtoonResponse {
        val all = catalog.getAllSeries()
        val pageSize = 24
        val from = (page - 1) * pageSize
        if (from >= all.size) return PagedWebtoonResponse(emptyList(), hasNextPage = false)
        val slice = all.drop(from).take(pageSize)
        return PagedWebtoonResponse(
            webtoons = slice.map { it.toWebtoon() },
            hasNextPage = from + pageSize < all.size,
        )
    }

    override suspend fun fetchSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val all = catalog.search(query)
        val pageSize = 24
        val from = (page - 1) * pageSize
        if (from >= all.size) return MangasPage(emptyList(), false)
        val slice = all.drop(from).take(pageSize)
        return MangasPage(
            mangas = slice.map { it.toWebtoon().toSManga() },
            hasNextPage = from + pageSize < all.size,
        )
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon {
        val detail = getMangaDetails(webtoonId)
        return detail.copy(chapters = getChapterList(webtoonId))
    }

    override suspend fun getMangaDetails(webtoonId: String): Webtoon {
        val series = catalog.getSeries(webtoonId)
            ?: throw IllegalArgumentException("Local series '$webtoonId' not found")
        return series.toWebtoon()
    }

    override suspend fun getChapterList(webtoonId: String): List<Chapter> {
        val series = catalog.getSeries(webtoonId) ?: return emptyList()
        return series.chapters.map { ch ->
            Chapter(
                id = ch.id,
                title = ch.title,
                number = ch.number,
            )
        }
    }

    override suspend fun getChapterPages(chapterId: String): List<String> {
        val chapter = catalog.getAllSeries()
            .flatMap { it.chapters }
            .firstOrNull { it.id == chapterId }
            ?: return emptyList()

        val path = chapter.localPath
        if (path.startsWith("content://")) return listOf(path)
        if (path.endsWith(".cbz", true) || path.endsWith(".zip", true)) return listOf("file://$path")
        val dir = java.io.File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { _, name -> LocalLibraryScanner.isPageFileName(name) }
            ?.sortedBy { it.name.substringBeforeLast('.').filter(Char::isDigit).toIntOrNull() ?: 0 }
            ?.map { "file://${it.absolutePath}" }
            .orEmpty()
    }

    private fun LocalSeries.toWebtoon(): Webtoon = Webtoon(
        id = id,
        title = title,
        thumbnail = coverPath.orEmpty(),
        status = "Local",
        description = "Stored on device at ${catalog.lastRootPath}",
    )

    private fun Webtoon.toSManga(): eu.kanade.tachiyomi.source.model.SManga =
        eu.kanade.tachiyomi.source.model.SManga.create().apply {
            url = id
            title = this@toSManga.title
            thumbnail_url = thumbnail
        }
}
