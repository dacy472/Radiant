package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the device WebView User-Agent on every OkHttp request so scrapers match
 * [android.webkit.WebSettings.getDefaultUserAgent] used by Cloudflare WebView bypass.
 *
 * [FALLBACK_USER_AGENT] is only used in unit tests or when WebView is unavailable.
 */
open class UserAgentInterceptor(
    private val defaultUserAgentProvider: () -> String = { FALLBACK_USER_AGENT },
) : Interceptor {

    constructor(initialUserAgent: String) : this({ initialUserAgent })

    @Volatile
    var userAgent: String = defaultUserAgentProvider()
        set(value) {
            if (value.isNotBlank()) field = value
        }

    override fun intercept(chain: Interceptor.Chain): Response {
        val ua = defaultUserAgentProvider().ifBlank { userAgent }.ifBlank { FALLBACK_USER_AGENT }
        if (ua != userAgent) userAgent = ua
        val request = chain.request().newBuilder()
            .header("User-Agent", ua)
            .build()
        return chain.proceed(request)
    }

    companion object {
        /** Last-resort UA when [WebSettings.getDefaultUserAgent] cannot be read. */
        const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Mobile Safari/537.36"

        const val DEFAULT_USER_AGENT: String = FALLBACK_USER_AGENT
    }
}
