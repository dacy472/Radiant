package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.*
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.util.asJsoup

/**
 * A helper class for sources that use Jsoup to parse HTML.
 * Many Tachiyomi extensions extend this class.
 */
abstract class ParsedHttpSource : HttpSource() {

    // ─── Popular Manga ───

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = popularMangaNextPageSelector()?.let {
            document.select(it).first()
        } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun popularMangaSelector(): String
    protected abstract fun popularMangaFromElement(element: Element): SManga
    protected abstract fun popularMangaNextPageSelector(): String?

    // ─── Search Manga ───

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        val hasNextPage = searchMangaNextPageSelector()?.let {
            document.select(it).first()
        } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun searchMangaSelector(): String
    protected abstract fun searchMangaFromElement(element: Element): SManga
    protected abstract fun searchMangaNextPageSelector(): String?

    // ─── Latest Updates ───

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }
        val hasNextPage = latestUpdatesNextPageSelector()?.let {
            document.select(it).first()
        } != null
        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun latestUpdatesSelector(): String
    protected abstract fun latestUpdatesFromElement(element: Element): SManga
    protected abstract fun latestUpdatesNextPageSelector(): String?

    // ─── Manga Details ───

    override fun mangaDetailsParse(response: Response): SManga {
        return mangaDetailsParse(response.asJsoup())
    }

    protected abstract fun mangaDetailsParse(document: Document): SManga

    // ─── Chapter List ───

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { element ->
            chapterFromElement(element)
        }
    }

    protected abstract fun chapterListSelector(): String
    protected abstract fun chapterFromElement(element: Element): SChapter

    // ─── Page List ───

    override fun pageListParse(response: Response): List<Page> {
        return pageListParse(response.asJsoup())
    }

    protected abstract fun pageListParse(document: Document): List<Page>

    // ─── Image URL ───

    override fun imageUrlParse(response: Response): String {
        return imageUrlParse(response.asJsoup())
    }

    protected open fun imageUrlParse(document: Document): String = ""
}
