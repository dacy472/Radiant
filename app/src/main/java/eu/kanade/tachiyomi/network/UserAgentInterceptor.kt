package eu.kanade.tachiyomi.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that appends a global User-Agent to every request.
 * This can be updated at runtime after a Cloudflare WebView bypass.
 */
class UserAgentInterceptor : Interceptor {

    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(request)
    }
}
