package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Merges origin-site Cloudflare cookies (`cf_clearance`, `__cf_bm`, …) onto a
 * request Cookie header so image CDNs can see the same verification the WebView solved.
 */
object CloudflareCookiePropagation {
    private val NAMES = setOf(
        "cf_clearance",
        "__cf_bm",
        "__cfruid",
        "cf_chl_2",
        "cf_chl_rc_ni",
        "cf_chl_rc_i",
    )

    fun isCloudflareCookie(name: String): Boolean {
        val lower = name.lowercase()
        return lower in NAMES || lower.startsWith("cf_") || lower.startsWith("__cf")
    }

    fun sameCookieSite(originHost: String, requestHost: String): Boolean {
        val origin = originHost.trim('.').lowercase()
        val dest = requestHost.trim('.').lowercase()
        if (origin == dest) return true
        if (dest.endsWith(".$origin")) return true
        val originRoot = registrableDomain(origin)
        val destRoot = registrableDomain(dest)
        return originRoot.isNotEmpty() && originRoot == destRoot
    }

    fun registrableDomain(host: String): String {
        val parts = host.trim('.').lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return host.lowercase()
        val last = parts.last()
        val second = parts[parts.size - 2]
        val multiPartTld = last.length == 2 && second.length <= 3 && parts.size >= 3
        return if (multiPartTld) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    fun mergeCookieHeader(existing: String?, extra: List<Cookie>): String? {
        if (extra.isEmpty()) return existing?.takeIf { it.isNotBlank() }
        val values = LinkedHashMap<String, String>()
        existing?.split(';')?.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            values[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
        }
        extra.forEach { cookie ->
            if (cookie.name.isNotBlank() && cookie.value.isNotBlank()) {
                val exists = values.keys.any { it.equals(cookie.name, ignoreCase = true) }
                if (!exists) values[cookie.name] = cookie.value
            }
        }
        if (values.isEmpty()) return null
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun cloudflareOnly(cookies: List<Cookie>): List<Cookie> =
        cookies.filter { isCloudflareCookie(it.name) && it.value.isNotBlank() }
}
