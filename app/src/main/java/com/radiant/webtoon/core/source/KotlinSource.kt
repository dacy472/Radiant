package com.radiant.webtoon.core.source

import com.radiant.webtoon.core.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Base class for sources written directly in Kotlin (no JS sandbox needed).
 * Provides OkHttp client and Jsoup parsing helpers.
 */
abstract class KotlinSource : Source {

    protected val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Fetch a URL and parse it as a Jsoup Document.
     */
    protected fun fetchDocument(url: String, headers: Map<String, String> = emptyMap()): org.jsoup.nodes.Document {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: ""
        return Jsoup.parse(body, url)
    }

    /**
     * Fetch a URL and return the raw response body as a string.
     */
    protected fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val response = client.newCall(requestBuilder.build()).execute()
        return response.body?.string() ?: ""
    }

    override suspend fun getWebtoonDetail(webtoonId: String): Webtoon {
        val detail = getMangaDetails(webtoonId)
        return detail.copy(chapters = getChapterList(webtoonId))
    }
}
