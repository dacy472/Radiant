package com.radiant.webtoon.core.source

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves per-source base URL overrides and rewrites request/page URLs from the
 * extension default host to a user-provided mirror.
 */
object BaseUrlResolver {

    fun effective(defaultBaseUrl: String, override: String?): String {
        val trimmedOverride = override?.trim().orEmpty()
        if (trimmedOverride.isBlank()) return normalizeBaseUrl(defaultBaseUrl)
        return normalizeBaseUrl(trimmedOverride)
    }

    fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

    fun isValidOverride(url: String): Boolean {
        val normalized = normalizeBaseUrl(url)
        if (normalized.isBlank()) return true
        val httpUrl = normalized.toHttpUrlOrNull() ?: return false
        return httpUrl.scheme == "http" || httpUrl.scheme == "https"
    }

    fun hostOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.toHttpUrlOrNull()?.host?.lowercase()
    }

    /**
     * When [url] targets [defaultBaseUrl]'s host, swap scheme/host/port to [effectiveBaseUrl].
     */
    fun rewriteUrl(url: String, defaultBaseUrl: String, effectiveBaseUrl: String): String {
        if (url.isBlank()) return url
        if (defaultBaseUrl.isBlank() || effectiveBaseUrl.isBlank()) return url
        if (normalizeBaseUrl(defaultBaseUrl) == normalizeBaseUrl(effectiveBaseUrl)) return url

        val sourceUrl = url.toHttpUrlOrNull() ?: return url
        val defaultHost = hostOf(defaultBaseUrl) ?: return url
        if (!sourceUrl.host.equals(defaultHost, ignoreCase = true)) return url

        val targetBase = effectiveBaseUrl.toHttpUrlOrNull()
            ?: "https://${normalizeBaseUrl(effectiveBaseUrl)}".toHttpUrlOrNull()
            ?: return url

        return sourceUrl.newBuilder()
            .scheme(targetBase.scheme)
            .host(targetBase.host)
            .port(targetBase.port)
            .build()
            .toString()
    }

    fun rewriteHttpUrl(url: HttpUrl, hostRewrites: Map<String, String>): HttpUrl {
        if (hostRewrites.isEmpty()) return url
        val replacementHost = hostRewrites[url.host.lowercase()] ?: return url
        return url.newBuilder().host(replacementHost).build()
    }

    fun rewriteReferer(referer: String, hostRewrites: Map<String, String>): String {
        if (hostRewrites.isEmpty() || referer.isBlank()) return referer
        val httpUrl = referer.toHttpUrlOrNull() ?: return referer
        val rewritten = rewriteHttpUrl(httpUrl, hostRewrites)
        return if (rewritten == httpUrl) referer else rewritten.toString()
    }

    /**
     * Maps default extension host (lowercase) → override host (lowercase) for OkHttp rewriting.
     */
    fun buildHostRewriteMap(
        sources: Collection<Source>,
        overrides: Map<String, String>,
    ): Map<String, String> {
        if (overrides.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (source in sources) {
            val override = overrides[source.id]?.trim().orEmpty()
            if (override.isBlank()) continue
            val defaultHost = hostOf(source.defaultBaseUrl) ?: continue
            val overrideHost = hostOf(override) ?: continue
            if (defaultHost != overrideHost) {
                map[defaultHost] = overrideHost
            }
        }
        return map
    }
}

val Source.defaultBaseUrl: String
    get() = when (this) {
        is TachiyomiSourceAdapter -> this.defaultBaseUrl
        else -> baseUrl
    }
