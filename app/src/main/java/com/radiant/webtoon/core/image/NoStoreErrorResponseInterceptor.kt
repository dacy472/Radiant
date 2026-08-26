package com.radiant.webtoon.core.image

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Marks unsuccessful HTTP responses as uncacheable so Coil does not keep error bodies
 * (hotlink 403, 503, Cloudflare Access Denied). Cloudflare error 1020 is usually a 403
 * with "1020" in HTML; [response.code] == 1020 is still handled if it appears as a status.
 */
class NoStoreErrorResponseInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful && response.code != 1020) {
            return response
        }
        return response.newBuilder()
            .addHeader("Cache-Control", "no-store")
            .build()
    }
}
