package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp [CookieJar] fully backed by [android.webkit.CookieManager].
 *
 * WebView Cloudflare solves write `cf_clearance` into CookieManager; every OkHttp call
 * (HTML and image CDNs) reads that same store. Cloudflare cookies are also indexed in
 * memory so [CloudflareCookieInterceptor] can attach them to cross-origin CDN requests
 * that CookieManager would not match by domain.
 */
class AndroidCookieJar(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
) : CookieJar {

    private val manager: CookieManager = CookieManager.getInstance().also { jar ->
        jar.setAcceptCookie(true)
    }

    private val cloudflareByHost = ConcurrentHashMap<String, Map<String, Cookie>>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val fromManager = get(url)
        indexCloudflare(url, fromManager)
        val extra = cloudflareCookies(url)
        if (extra.isEmpty()) return fromManager
        val names = fromManager.map { it.name.lowercase() }.toHashSet()
        return fromManager + extra.filter { it.name.lowercase() !in names }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(manager) {
            for (cookie in cookies) {
                val target = cookieTargetUrl(url, cookie)
                manager.setCookie(target, cookie.toString())
            }
            flush()
        }
        indexCloudflare(url, cookies)
    }

    fun get(url: HttpUrl): List<Cookie> {
        return try {
            val raw = synchronized(manager) { manager.getCookie(url.toString()) } ?: return emptyList()
            parseCookieHeader(url, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getCookieHeader(url: HttpUrl): String? {
        return try {
            synchronized(manager) { manager.getCookie(url.toString()) }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-read WebView cookies for [url] after a challenge, persist them, and index
     * `cf_clearance` for CDN reuse.
     */
    fun syncFromWebView(url: HttpUrl, rawCookieHeader: String? = null) {
        val raw = rawCookieHeader ?: getCookieHeader(url) ?: return
        val cookies = parseCookieHeader(url, raw)
        if (cookies.isEmpty()) return
        saveFromResponse(url, cookies)
        val hostOrigin = "${if (url.isHttps) "https" else "http"}://${url.host}/".toHttpUrlOrNull()
        if (hostOrigin != null && hostOrigin != url) {
            saveFromResponse(hostOrigin, cookies)
        }
        // Also write host-only CF cookies onto the registrable domain so subdomains see them.
        val root = CloudflareCookiePropagation.registrableDomain(url.host)
        if (root.isNotBlank() && !root.equals(url.host, ignoreCase = true)) {
            val rootUrl = "https://$root/".toHttpUrlOrNull()
            if (rootUrl != null) {
                val cf = CloudflareCookiePropagation.cloudflareOnly(cookies)
                if (cf.isNotEmpty()) {
                    synchronized(manager) {
                        cf.forEach { cookie ->
                            manager.setCookie(
                                rootUrl.toString(),
                                "${cookie.name}=${cookie.value}; Path=/; Domain=.$root; Secure",
                            )
                        }
                        flush()
                    }
                    indexCloudflare(rootUrl, cf)
                }
            }
        }
    }

    fun cloudflareCookies(url: HttpUrl): List<Cookie> {
        val merged = LinkedHashMap<String, Cookie>()
        fun take(host: String) {
            cloudflareByHost[host.lowercase()]?.forEach { (name, cookie) ->
                merged.putIfAbsent(name.lowercase(), cookie)
            }
        }
        take(url.host)
        take(CloudflareCookiePropagation.registrableDomain(url.host))
        CloudflareCookiePropagation.cloudflareOnly(get(url)).forEach { cookie ->
            merged.putIfAbsent(cookie.name.lowercase(), cookie)
        }
        return merged.values.toList()
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null) {
        synchronized(manager) {
            for (cookie in get(url)) {
                if (cookieNames != null && cookie.name !in cookieNames) continue
                manager.setCookie(
                    url.toString(),
                    "${cookie.name}=; Max-Age=0; Path=${cookie.path}",
                )
            }
            flush()
        }
        val hostKey = url.host.lowercase()
        if (cookieNames == null) {
            cloudflareByHost.remove(hostKey)
            cloudflareByHost.remove(CloudflareCookiePropagation.registrableDomain(url.host))
        } else {
            cookieNames.forEach { name ->
                cloudflareByHost[hostKey]?.minus(name)?.let { cloudflareByHost[hostKey] = it }
            }
        }
    }

    fun clear() {
        try {
            synchronized(manager) {
                manager.removeAllCookies(null)
                flush()
            }
            cloudflareByHost.clear()
        } catch (_: Exception) {
            // Best-effort
        }
    }

    private fun parseCookieHeader(url: HttpUrl, raw: String): List<Cookie> {
        return raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .mapNotNull { part -> Cookie.parse(url, part) }
    }

    private fun indexCloudflare(url: HttpUrl, cookies: List<Cookie>) {
        val cf = CloudflareCookiePropagation.cloudflareOnly(cookies)
        if (cf.isEmpty()) return
        fun store(host: String) {
            val key = host.lowercase()
            val current = cloudflareByHost[key]?.toMutableMap() ?: mutableMapOf()
            cf.forEach { current[it.name] = it }
            cloudflareByHost[key] = current
        }
        store(url.host)
        store(CloudflareCookiePropagation.registrableDomain(url.host))
    }

    private fun cookieTargetUrl(requestUrl: HttpUrl, cookie: Cookie): String {
        val domain = cookie.domain.trim('.').ifBlank { requestUrl.host }
        val scheme = if (requestUrl.isHttps) "https" else "http"
        val path = cookie.path.ifBlank { "/" }
        return "$scheme://$domain$path"
    }

    private fun flush() {
        try {
            manager.flush()
        } catch (_: Exception) {
            // flush is best-effort
        }
    }
}
