package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds a User-Agent only when the request has none (Mihon behaviour).
 *
 * Extensions that pin their own UA keep it: the `cf_clearance` Cloudflare issues is bound to
 * the exact UA that solved the challenge, so rewriting the header after the fact makes every
 * clearance useless and forces a fresh challenge on each call.
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
        val original = chain.request()
        if (!original.header("User-Agent").isNullOrEmpty()) {
            return chain.proceed(original)
        }
        val ua = userAgent.ifBlank { defaultUserAgentProvider() }.ifBlank { FALLBACK_USER_AGENT }
        if (ua != userAgent) userAgent = ua
        return chain.proceed(
            original.newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", ua)
                .build(),
        )
    }

    companion object {
        /** Last-resort UA when [android.webkit.WebSettings.getDefaultUserAgent] cannot be read. */
        const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Mobile Safari/537.36"

        const val DEFAULT_USER_AGENT: String = FALLBACK_USER_AGENT
    }
}
