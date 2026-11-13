package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.UserAgentInterceptor
import eu.kanade.tachiyomi.network.await
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
        // Device WebView UA — same string OkHttp sends on every request.
        add("User-Agent", network.defaultUserAgentProvider())
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

    // NOTE: These are `open` (not `abstract`) to match modern Mihon/Keiyoushi's extensions-lib.
    // Newer extensions often skip this Request/Parse pattern entirely and instead override the
    // suspend getPopularManga/getSearchManga/getLatestUpdates contract directly. Declaring these
    // as abstract here would force the JVM to require an override that a modern extension never
    // provides, causing AbstractMethodError at runtime when this fallback path is invoked.
    protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ─── Search Manga ───

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = searchMangaRequest(page, query, filters)
            val response = client.newCall(request).execute()
            searchMangaParse(response)
        }
    }

    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ─── Latest Updates ───

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = latestUpdatesRequest(page)
            val response = client.newCall(request).execute()
            latestUpdatesParse(response)
        }
    }

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

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

    protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

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

    protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

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

    protected open fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    /**
     * Fetches the page list and returns HTTP status metadata alongside parsed pages.
     * Used by the reader to distinguish Cloudflare blocks from genuinely empty chapters.
     */
    @Suppress("DEPRECATION")
    fun fetchPageListWithMetadata(chapter: SChapter): PageListFetchResult {
        return try {
            val request = pageListRequest(chapter)
            client.newCall(request).execute().use { response ->
                val status = response.code
                val chapterUrl = request.url.toString()
                val peekedBody = try {
                    response.peekBody(100_000L).string()
                } catch (_: Exception) {
                    null
                }
                val challengeHtml = eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeDetector
                    .peekChallengeHtml(response)
                val statusChallenge = status in eu.kanade.tachiyomi.network.interceptor
                    .CloudflareChallengeDetector.CHALLENGE_STATUS_CODES &&
                    (
                        eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeDetector
                            .isChallengeResponse(response) ||
                            challengeHtml
                        )
                val pages = try {
                    pageListParse(response)
                } catch (e: Exception) {
                    com.radiant.webtoon.core.debug.PageListDebugLogger.logParseFailure(
                        step = "pageListParse_exception",
                        error = e,
                        responseBody = peekedBody,
                    )
                    emptyList()
                }
                val cloudflareChallenge = statusChallenge ||
                    (pages.isEmpty() && challengeHtml && status != 404)
                if (pages.isEmpty()) {
                    com.radiant.webtoon.core.debug.PageListDebugLogger.logEmptyPageList(
                        step = if (cloudflareChallenge) "empty_after_parse_cloudflare" else "empty_after_parse",
                        httpStatus = status,
                        responseBody = peekedBody,
                        strategy = "HttpSource.pageListParse",
                        extra = mapOf("pageCount" to "0"),
                    )
                } else if (status !in 200..299) {
                    com.radiant.webtoon.core.debug.PageListDebugLogger.logStep(
                        step = "non_2xx_with_pages",
                        httpStatus = status,
                        responseBody = peekedBody,
                        strategy = "HttpSource.pageListParse",
                        extra = mapOf("pageCount" to pages.size.toString()),
                    )
                }
                PageListFetchResult(
                    pages = pages,
                    httpStatus = status,
                    chapterUrl = chapterUrl,
                    cloudflareChallenge = cloudflareChallenge,
                )
            }
        } catch (e: java.io.IOException) {
            val isCloudflare = e.message?.contains("cloudflare", ignoreCase = true) == true
            com.radiant.webtoon.core.debug.PageListDebugLogger.logEmptyPageList(
                step = "fetch_io_exception",
                httpStatus = if (isCloudflare) 403 else -1,
                error = e,
                strategy = "HttpSource.fetchPageListWithMetadata",
            )
            PageListFetchResult(
                pages = emptyList(),
                httpStatus = if (isCloudflare) 403 else -1,
                chapterUrl = getChapterUrl(chapter),
                cloudflareChallenge = isCloudflare,
            )
        }
    }

    // ─── Image URL ───

    @Deprecated("Use the suspend API instead", ReplaceWith("getImageUrl"))
    open fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            val request = imageUrlRequest(page)
            val response = client.newCall(request).execute()
            imageUrlParse(response)
        }
    }

    /**
     * Suspend contract for resolving a page's image url. Extensions may override this directly
     * instead of the deprecated Observable-based [fetchImageUrl]/[imageUrlRequest]/[imageUrlParse].
     */
    @Suppress("DEPRECATION")
    open suspend fun getImageUrl(page: Page): String = fetchImageUrl(page).toBlocking().first()

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

    /**
     * Mihon source-api: downloads [page] with this source's own [client] and [imageRequest], so
     * per-extension interceptors (descramblers, signed URLs, cookies) run exactly as in Mihon.
     * Extensions may override it. Throws on a non-2xx response.
     */
    open suspend fun getImage(page: Page): Response {
        val response = client.newCall(imageRequest(page)).await()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("HTTP error ${response.code}")
        }
        return response
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
        /** Fallback only — prefer [NetworkHelper.defaultUserAgentProvider] (WebView UA) at runtime. */
        const val DEFAULT_USER_AGENT = UserAgentInterceptor.FALLBACK_USER_AGENT
    }
}
