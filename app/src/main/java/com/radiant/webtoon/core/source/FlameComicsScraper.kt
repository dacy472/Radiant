package com.radiant.webtoon.core.source

import com.radiant.webtoon.core.debug.PageListDebugLogger
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.PageListJsDecryptor
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Native Flame Comics chapter scraper. Fetches chapter HTML, parses `__NEXT_DATA__`
 * with Jsoup, and builds CDN image URLs without relying on the Tachiyomi extension APK.
 *
 * QuickJS decrypt remains a fallback when image data is encrypted.
 */
class FlameComicsScraper(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
    private val jsEngine: JavaScriptEngine? = null,
    private val baseUrl: String? = null,
) {

    /**
     * Download [chapterUrl], parse `__NEXT_DATA__`, and return image URLs.
     */
    fun fetchPageList(chapterUrl: String): List<String> {
        val htmlResult = fetchBody(chapterUrl)
        val html = htmlResult.body
        if (html.isNullOrBlank()) {
            PageListDebugLogger.logEmptyPageList(
                step = "flame_scraper_html_fetch_failed",
                httpStatus = htmlResult.status,
                responseBody = htmlResult.body,
                error = htmlResult.error,
                strategy = "FlameComicsScraper.fetchPageList",
                extra = mapOf("url" to chapterUrl),
            )
            return emptyList()
        }

        if (looksLikeCloudflare(html, htmlResult.status)) {
            PageListDebugLogger.logEmptyPageList(
                step = "flame_scraper_cloudflare_challenge",
                httpStatus = htmlResult.status,
                responseBody = html,
                strategy = "FlameComicsScraper.fetchPageList",
                extra = mapOf("url" to chapterUrl),
            )
            return emptyList()
        }

        PageListDebugLogger.logStep(
            step = "flame_scraper_html_fetched",
            httpStatus = htmlResult.status,
            responseBody = html,
            strategy = "OkHttp+Jsoup",
            extra = mapOf("url" to chapterUrl),
        )

        val nextDataJson = extractNextDataJson(html)
        if (nextDataJson.isNullOrBlank()) {
            PageListDebugLogger.logStep(
                step = "flame_scraper_missing_next_data",
                httpStatus = htmlResult.status,
                responseBody = html,
                strategy = "script#__NEXT_DATA__",
            )
        } else {
            val fromNextData = parseChapterImageUrls(nextDataJson, preferPropsPath = true)
            val encrypted = PageListJsDecryptor.extractEncryptedImagesField(nextDataJson)
            if (fromNextData.isNotEmpty()) {
                PageListDebugLogger.logStep(
                    step = "flame_scraper_next_data_success",
                    strategy = "script#__NEXT_DATA__",
                    extra = mapOf("pageCount" to fromNextData.size.toString()),
                )
                return fromNextData
            }

            if (!encrypted.isNullOrBlank() && jsEngine != null) {
                val decrypted = PageListJsDecryptor.decryptPayload(encrypted, html, jsEngine)
                if (decrypted.isNotEmpty()) {
                    PageListDebugLogger.logStep(
                        step = "flame_scraper_next_data_decrypt_success",
                        strategy = "PageListJsDecryptor.decryptPayload",
                        extra = mapOf("pageCount" to decrypted.size.toString()),
                    )
                    return decrypted
                }
            }
        }

        // Next.js data API fallback (same payload shape as pageProps).
        val apiUrls = fetchFromNextDataApi(chapterUrl, nextDataJson)
        if (apiUrls.isNotEmpty()) {
            PageListDebugLogger.logStep(
                step = "flame_scraper_api_success",
                strategy = "/_next/data API",
                extra = mapOf("pageCount" to apiUrls.size.toString()),
            )
            return apiUrls
        }

        // QuickJS decrypt last resort.
        if (jsEngine != null) {
            val decrypted = PageListJsDecryptor.decryptFromHtml(html, jsEngine)
            if (decrypted.isNotEmpty()) {
                PageListDebugLogger.logStep(
                    step = "flame_scraper_js_decrypt_success",
                    strategy = "PageListJsDecryptor.decryptFromHtml",
                    extra = mapOf("pageCount" to decrypted.size.toString()),
                )
                return decrypted
            }
            PageListDebugLogger.logStep(
                step = "flame_scraper_js_decrypt_empty",
                responseBody = html,
                strategy = "PageListJsDecryptor.decryptFromHtml",
            )
        }

        PageListDebugLogger.logEmptyPageList(
            step = "flame_scraper_empty",
            httpStatus = htmlResult.status,
            responseBody = html,
            strategy = "FlameComicsScraper.fetchPageList",
            extra = mapOf("url" to chapterUrl),
        )
        return emptyList()
    }

    private fun extractNextDataJson(html: String): String? {
        return try {
            val document = Jsoup.parse(html)
            document.selectFirst("script#__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("flame_scraper_jsoup_next_data", e, html)
            null
        }
    }

    private fun fetchFromNextDataApi(chapterUrl: String, nextDataJson: String?): List<String> {
        return try {
            val parsedChapter = chapterUrl.toHttpUrl()
            val seriesId = parsedChapter.pathSegments.getOrNull(1) ?: return emptyList()
            val token = parsedChapter.pathSegments.getOrNull(2) ?: return emptyList()
            val origin = baseUrl?.takeIf { it.isNotBlank() }
                ?: "${parsedChapter.scheme}://${parsedChapter.host}"

            val buildId = extractBuildId(nextDataJson)
                ?: extractBuildIdFromHtml(fetchBody(origin).body)
                ?: run {
                    PageListDebugLogger.logStep(
                        step = "flame_scraper_missing_build_id",
                        strategy = "/_next/data API",
                    )
                    return emptyList()
                }

            val apiUrl = origin.toHttpUrl().newBuilder()
                .addPathSegment("_next")
                .addPathSegment("data")
                .addPathSegment(buildId)
                .addPathSegment("series")
                .addPathSegment(seriesId)
                .addPathSegment("$token.json")
                .addQueryParameter("id", seriesId)
                .addQueryParameter("token", token)
                .build()
                .toString()

            val apiResult = fetchBody(apiUrl)
            val apiJson = apiResult.body
            if (apiJson.isNullOrBlank()) {
                PageListDebugLogger.logEmptyPageList(
                    step = "flame_scraper_api_fetch_failed",
                    httpStatus = apiResult.status,
                    responseBody = apiResult.body,
                    error = apiResult.error,
                    strategy = "/_next/data API",
                    extra = mapOf("apiUrl" to apiUrl),
                )
                return emptyList()
            }

            PageListDebugLogger.logStep(
                step = "flame_scraper_api_fetched",
                httpStatus = apiResult.status,
                responseBody = apiJson,
                strategy = "/_next/data API",
                extra = mapOf("apiUrl" to apiUrl),
            )

            val parsed = parseChapterImageUrls(apiJson, preferPropsPath = false)
            if (parsed.isNotEmpty()) return parsed

            val encrypted = PageListJsDecryptor.extractEncryptedImagesField(apiJson)
            if (!encrypted.isNullOrBlank() && jsEngine != null) {
                val chapterHtml = fetchBody(chapterUrl).body.orEmpty()
                val decrypted = PageListJsDecryptor.decryptPayload(encrypted, chapterHtml, jsEngine)
                if (decrypted.isNotEmpty()) return decrypted
            }
            emptyList()
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("flame_scraper_api_exception", e)
            emptyList()
        }
    }

    /**
     * Parse Flame chapter image metadata from `__NEXT_DATA__` or `/_next/data` JSON.
     */
    internal fun parseChapterImageUrls(jsonBody: String, preferPropsPath: Boolean): List<String> {
        return try {
            val root = json.parseToJsonElement(jsonBody).jsonObject
            val chapter = findChapterObject(root, preferPropsPath) ?: run {
                PageListDebugLogger.logStep(
                    step = "flame_scraper_missing_chapter_object",
                    responseBody = jsonBody,
                    strategy = "parseChapterImageUrls",
                )
                return emptyList()
            }

            val seriesId = chapter["series_id"]?.jsonPrimitive?.content
            val token = chapter["token"]?.jsonPrimitive?.content
            val releaseDate = chapter["release_date"]?.jsonPrimitive?.content
            val images = chapter["images"]
            if (seriesId == null || token == null || releaseDate == null || images == null) {
                return emptyList()
            }

            when (images) {
                is JsonPrimitive -> {
                    // Encrypted blob — caller handles decrypt.
                    if (images.isString && images.content.length > 40) emptyList() else emptyList()
                }
                else -> {
                    val names = imageNames(images)
                    names.map { name ->
                        "$CDN/uploads/images/series/$seriesId/$token/$name?$releaseDate"
                    }
                }
            }
        } catch (e: Exception) {
            PageListDebugLogger.logParseFailure("flame_scraper_parse_exception", e, jsonBody)
            emptyList()
        }
    }

    private fun findChapterObject(root: JsonObject, preferPropsPath: Boolean): JsonObject? {
        if (preferPropsPath) {
            root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("chapter")?.jsonObject
                ?.let { return it }
        }
        root["pageProps"]?.jsonObject
            ?.get("chapter")?.jsonObject
            ?.let { return it }
        root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("chapter")?.jsonObject
            ?.let { return it }
        // Walk for a nested chapter with series_id + images.
        return findNestedChapter(root)
    }

    private fun findNestedChapter(element: JsonElement): JsonObject? {
        when (element) {
            is JsonObject -> {
                if (element.containsKey("series_id") && element.containsKey("images") &&
                    element.containsKey("token")
                ) {
                    return element
                }
                element.values.forEach { child ->
                    findNestedChapter(child)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { child ->
                findNestedChapter(child)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun imageNames(images: JsonElement): List<String> {
        return when (images) {
            is JsonArray -> images.mapNotNull { element ->
                element.jsonObject["name"]?.jsonPrimitive?.content
            }
            is JsonObject -> images.values.mapNotNull { element ->
                element.jsonObject["name"]?.jsonPrimitive?.content
            }
            else -> emptyList()
        }
    }

    private fun extractBuildId(nextDataJson: String?): String? {
        if (nextDataJson.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(nextDataJson).jsonObject["buildId"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            Regex(""""buildId"\s*:\s*"([^"]+)"""").find(nextDataJson)?.groupValues?.getOrNull(1)
        }
    }

    private fun extractBuildIdFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        extractNextDataJson(html)?.let { extractBuildId(it)?.let { id -> return id } }
        return Regex(""""buildId"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeCloudflare(body: String, status: Int): Boolean {
        if (status == 403 || status == 503) {
            val lower = body.lowercase()
            return lower.contains("cloudflare") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("just a moment") ||
                lower.contains("attention required")
        }
        return false
    }

    private data class FetchResult(
        val body: String?,
        val status: Int,
        val error: Throwable? = null,
    )

    private fun fetchBody(url: String): FetchResult {
        return try {
            client.newCall(GET(url, headers)).execute().use { response ->
                val status = response.code
                val body = try {
                    response.body.string()
                } catch (e: Exception) {
                    PageListDebugLogger.logHttpFetch(url, status, null, e)
                    return FetchResult(null, status, e)
                }
                if (!response.isSuccessful) {
                    PageListDebugLogger.logHttpFetch(url, status, body)
                    return FetchResult(body.takeIf { status == 403 || status == 503 }, status)
                }
                FetchResult(body, status)
            }
        } catch (e: Exception) {
            PageListDebugLogger.logHttpFetch(url, -1, null, e)
            FetchResult(null, -1, e)
        }
    }

    companion object {
        private const val CDN = "https://cdn.flamecomics.xyz"

        private val json = Json { ignoreUnknownKeys = true }

        fun from(httpSource: HttpSource, jsEngine: JavaScriptEngine?): FlameComicsScraper {
            return FlameComicsScraper(
                client = httpSource.client,
                headers = httpSource.headers,
                jsEngine = jsEngine,
                baseUrl = httpSource.baseUrl,
            )
        }

        fun matchesSource(name: String, id: String, baseUrl: String = ""): Boolean {
            return name.contains("Flame Comics", ignoreCase = true) ||
                id.contains("flamecomics", ignoreCase = true) ||
                baseUrl.contains("flamecomics", ignoreCase = true)
        }
    }
}
