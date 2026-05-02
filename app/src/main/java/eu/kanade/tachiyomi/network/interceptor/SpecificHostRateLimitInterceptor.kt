package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.HttpUrl

@JvmOverloads
fun SpecificHostRateLimitInterceptor(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long,
    unit: java.util.concurrent.TimeUnit = java.util.concurrent.TimeUnit.SECONDS
): Interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
