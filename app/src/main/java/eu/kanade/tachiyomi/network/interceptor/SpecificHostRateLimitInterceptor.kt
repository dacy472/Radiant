package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@JvmOverloads
fun SpecificHostRateLimitInterceptor(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long,
    unit: TimeUnit = TimeUnit.SECONDS,
): Interceptor {
    val duration = unit.toMillis(period).milliseconds
    return RateLimitInterceptor(host = httpUrl.host, permits = permits, period = duration)
}

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val duration = unit.toMillis(period).milliseconds
    return addInterceptor(RateLimitInterceptor(host = httpUrl.host, permits = permits, period = duration))
}

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder =
    addInterceptor(RateLimitInterceptor(host = httpUrl.host, permits = permits, period = period))
