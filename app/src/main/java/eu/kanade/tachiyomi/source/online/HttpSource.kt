package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A simple implementation for sources from a website.
 * This is the class most Tachiyomi/Mihon extensions extend.
 */
@Suppress("unused", "unused_parameter")
abstract class HttpSource : CatalogueSource {

    /**
     * Network service — provides OkHttpClient instances.
     */
    open val network: NetworkHelper = Injekt.get()

    /**
     * Base url of the website without the trailing slash.
     */
    abstract val baseUrl: String

    /**
     * Version id used to generate the source id.
     */
    open val versionId: Int = 1

    /**
     * Id of the source. Generated from name/language/versionId.
     */
    override val id: Long by lazy {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val high = bytes.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        high and Long.MAX_VALUE // clear sign bit
    }

    /**
     * Headers used for requests.
     */
    val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for doing requests.
     */
    open val client: OkHttpClient = network.client

    /**
     * Headers builder for requests.
     */
    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", DEFAULT_USER_AGENT)
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // ─── Popular Manga ───

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = popularMangaRequest(page)
            val response = client.newCall(request).execute()
            popularMangaParse(response)
        }
    }

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun popularMangaParse(response: Response): MangasPage

    // ─── Search Manga ───

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = searchMangaRequest(page, query, filters)
            val response = client.newCall(request).execute()
            searchMangaParse(response)
        }
    }

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected abstract fun searchMangaParse(response: Response): MangasPage

    // ─── Latest Updates ───

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = latestUpdatesRequest(page)
            val response = client.newCall(request).execute()
            latestUpdatesParse(response)
        }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    // ─── Manga Details ───

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val request = mangaDetailsRequest(manga)
            val response = client.newCall(request).execute()
            mangaDetailsParse(response).apply { initialized = true }
        }
    }

    open fun mangaDetailsRequest(manga: SManga): Request {
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    // ─── Chapter List ───

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val request = chapterListRequest(manga)
            val response = client.newCall(request).execute()
            chapterListParse(response)
        }
    }

    protected open fun chapterListRequest(manga: SManga): Request {
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    // ─── Page List ───

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val request = pageListRequest(chapter)
            val response = client.newCall(request).execute()
            pageListParse(response)
        }
    }

    protected open fun pageListRequest(chapter: SChapter): Request {
        return Request.Builder()
            .url(baseUrl + chapter.url)
            .headers(headers)
            .build()
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    // ─── Image URL ───

    open fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            val request = imageUrlRequest(page)
            val response = client.newCall(request).execute()
            imageUrlParse(response)
        }
    }

    protected open fun imageUrlRequest(page: Page): Request {
        return Request.Builder()
            .url(page.url)
            .headers(headers)
            .build()
    }

    protected open fun imageUrlParse(response: Response): String = ""

    // ─── Image Request ───

    open fun fetchImage(page: Page): Observable<Response> {
        return Observable.fromCallable {
            val request = imageRequest(page)
            client.newCall(request).execute()
        }
    }

    protected open fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: page.url
        return Request.Builder()
            .url(url)
            .headers(headers)
            .build()
    }

    // ─── URL Helpers ───

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = java.net.URI(orig)
            var out = uri.path.orEmpty()
            if (uri.query != null) out += "?" + uri.query
            if (uri.fragment != null) out += "#" + uri.fragment
            out
        } catch (e: Exception) {
            orig
        }
    }

    // ─── Manga / Chapter URL ───

    open fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    open fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}

    override fun getFilterList(): FilterList = FilterList()

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
