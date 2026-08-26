package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor (runs after OkHttp attaches [CookieJar] cookies).
 *
 * Image CDNs such as `2xstorage.com` are a different host than the manga site, so
 * CookieManager will not send `cf_clearance` on those requests. When the request
 * carries a Referer to the origin site, this interceptor copies that origin's
 * Cloudflare cookies onto the outbound Cookie header.
 */
class CloudflareCookieInterceptor(
    private val cookieJar: AndroidCookieJar,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val referer = request.header("Referer")?.toHttpUrlOrNull()
            ?: return chain.proceed(request)
        if (CloudflareCookiePropagation.sameCookieSite(referer.host, request.url.host)) {
            return chain.proceed(request)
        }
        val extra = cookieJar.cloudflareCookies(referer)
        if (extra.isEmpty()) return chain.proceed(request)
        val merged = CloudflareCookiePropagation.mergeCookieHeader(request.header("Cookie"), extra)
            ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Cookie", merged).build())
    }
}
