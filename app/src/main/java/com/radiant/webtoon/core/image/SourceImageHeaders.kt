package com.radiant.webtoon.core.image

import com.radiant.webtoon.core.source.Source
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Headers Coil/OkHttp must send when fetching covers and chapter images.
 *
 * Extensions expose a [okhttp3.Headers] object (User-Agent, Referer, cookies, …).
 * Image CDNs such as `2xstorage.com` reject hotlinks unless Referer is the
 * origin site (`baseUrl`), not the CDN host.
 */
object SourceImageHeaders {
    const val SOURCE_ID_HEADER = "X-Radiant-Source-Id"
    const val REFERER = "Referer"
    const val USER_AGENT = "User-Agent"

    fun from(source: Source?): Headers {
        if (source == null) return Headers.headersOf()
        val builder = Headers.Builder()
        copyInto(builder, nativeHeadersFrom(source), skipSourceId = true)
        ensureReferer(builder, source.baseUrl)
        ensureUserAgent(builder, defaultNetworkUserAgent())
        return builder.build()
    }

    /**
     * Merge [source] defaults with [explicit] request headers.
     * Explicit values win per header name; all values for that name are kept
     * (no Map collapse). Header name casing from the winning side is preserved.
     */
    fun resolve(
        explicit: Headers,
        source: Source?,
        defaultUserAgent: String = defaultNetworkUserAgent(),
    ): Headers {
        val builder = Headers.Builder()
        copyInto(builder, from(source), skipSourceId = true)
        overlay(builder, explicit)
        ensureReferer(builder, source?.baseUrl.orEmpty())
        ensureUserAgent(builder, defaultUserAgent)
        return builder.build()
    }

    /** Thin adapter: converts [explicit] once at a Map boundary. */
    fun resolve(
        explicit: Map<String, String>,
        source: Source?,
        defaultUserAgent: String = defaultNetworkUserAgent(),
    ): Headers = resolve(explicit.toOkHttpHeaders(), source, defaultUserAgent)

    /**
     * Same string OkHttp sends: [NetworkHelper.defaultUserAgentProvider]
     * (custom Advanced Settings UA, else device WebView UA).
     */
    fun defaultNetworkUserAgent(): String {
        return runCatching {
            Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: UserAgentInterceptor.FALLBACK_USER_AGENT
    }

    fun toOkHttpHeaders(source: Source?): Headers = from(source)

    fun ensureReferer(builder: Headers.Builder, baseUrl: String) {
        if (builder[REFERER] != null) return
        val referer = originReferer(baseUrl)
        if (referer.isNotBlank()) {
            runCatching { builder.add(REFERER, referer) }
        }
    }

    fun ensureReferer(headers: MutableMap<String, String>, baseUrl: String) {
        val builder = headers.toOkHttpHeaders().newBuilder()
        ensureReferer(builder, baseUrl)
        replaceMap(headers, builder.build())
    }

    fun ensureUserAgent(builder: Headers.Builder, userAgent: String) {
        if (builder[USER_AGENT] != null) return
        if (userAgent.isNotBlank()) {
            runCatching { builder.add(USER_AGENT, userAgent) }
        }
    }

    fun ensureUserAgent(headers: MutableMap<String, String>, userAgent: String) {
        val builder = headers.toOkHttpHeaders().newBuilder()
        ensureUserAgent(builder, userAgent)
        replaceMap(headers, builder.build())
    }

    /** Origin site URL with trailing slash, e.g. `https://manhwa18.cc/`. */
    fun originReferer(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val httpUrl = withScheme.toHttpUrlOrNull()
        if (httpUrl != null) {
            val defaultPort = if (httpUrl.scheme == "https") 443 else 80
            val port = if (httpUrl.port != defaultPort) ":${httpUrl.port}" else ""
            return "${httpUrl.scheme}://${httpUrl.host}$port/"
        }
        return withScheme.trimEnd('/') + "/"
    }

    fun hasHeader(headers: Headers, name: String): Boolean = headers[name] != null

    fun hasHeader(headers: Map<String, String>, name: String): Boolean =
        headers.keys.any { it.equals(name, ignoreCase = true) }

    fun withoutSourceId(headers: Headers): Headers {
        val builder = Headers.Builder()
        copyInto(builder, headers, skipSourceId = true)
        return builder.build()
    }

    private fun nativeHeadersFrom(source: Source): Headers {
        if (source is TachiyomiSourceAdapter) return source.httpHeaders
        return source.getWebViewHeaders().toOkHttpHeaders()
    }

    private fun overlay(builder: Headers.Builder, explicit: Headers) {
        val replaced = HashSet<String>()
        for (i in 0 until explicit.size) {
            val name = explicit.name(i)
            if (name.equals(SOURCE_ID_HEADER, ignoreCase = true)) continue
            val value = explicit.value(i)
            if (value.isBlank()) continue
            val key = name.lowercase()
            if (replaced.add(key)) {
                builder.removeAll(name)
            }
            runCatching { builder.add(name, value) }
        }
    }

    private fun copyInto(builder: Headers.Builder, headers: Headers, skipSourceId: Boolean) {
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            if (skipSourceId && name.equals(SOURCE_ID_HEADER, ignoreCase = true)) continue
            val value = headers.value(i)
            if (value.isBlank()) continue
            runCatching { builder.add(name, value) }
        }
    }

    private fun replaceMap(target: MutableMap<String, String>, headers: Headers) {
        target.clear()
        for (i in 0 until headers.size) {
            target[headers.name(i)] = headers.value(i)
        }
    }
}

fun Map<String, String>.toOkHttpHeaders(): Headers {
    if (isEmpty()) return Headers.headersOf()
    val builder = Headers.Builder()
    forEach { (key, value) ->
        if (value.isNotBlank()) {
            runCatching { builder.add(key, value) }
        }
    }
    return builder.build()
}
