package eu.kanade.tachiyomi.network.interceptor

import com.radiant.webtoon.core.source.BaseUrlOverrideRegistry
import com.radiant.webtoon.core.source.BaseUrlResolver
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Rewrites OkHttp request URLs (and Referer headers) from a source's default domain
 * to a user-configured mirror. Extensions still emit URLs against their compiled
 * [eu.kanade.tachiyomi.source.online.HttpSource.baseUrl]; this interceptor transparently
 * retargets those requests at runtime.
 */
class BaseUrlRewriteInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val rewrites = BaseUrlOverrideRegistry.snapshot()
        if (rewrites.isEmpty()) {
            return chain.proceed(chain.request())
        }
        return chain.proceed(rewriteRequest(chain.request(), rewrites))
    }

    private fun rewriteRequest(request: Request, rewrites: Map<String, String>): Request {
        val rewrittenUrl = BaseUrlResolver.rewriteHttpUrl(request.url, rewrites)
        val builder = request.newBuilder().url(rewrittenUrl)

        val referer = request.header("Referer")
        if (!referer.isNullOrBlank()) {
            val rewrittenReferer = BaseUrlResolver.rewriteReferer(referer, rewrites)
            if (rewrittenReferer != referer) {
                builder.header("Referer", rewrittenReferer)
            }
        }
        return builder.build()
    }
}
