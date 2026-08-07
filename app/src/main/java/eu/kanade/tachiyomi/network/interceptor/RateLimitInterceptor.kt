@file:Keep
package eu.kanade.tachiyomi.network.interceptor

import androidx.annotation.Keep
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OkHttp interceptor that enforces a maximum number of requests per time window.
 * Matches the contract expected by Keiyoushi/Mihon extensions.
 */
class RateLimitInterceptor(
    private val host: String? = null,
    private val permits: Int,
    private val period: Duration,
) : Interceptor {

    private val lock = Any()
    private val timestamps = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (host != null && request.url.host != host) {
            return chain.proceed(request)
        }

        synchronized(lock) {
            val periodMs = period.inWholeMilliseconds
            val now = System.currentTimeMillis()
            while (timestamps.isNotEmpty() && now - timestamps.first() >= periodMs) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= permits) {
                val waitMs = periodMs - (now - timestamps.first())
                if (waitMs > 0) {
                    Thread.sleep(waitMs)
                }
                while (timestamps.isNotEmpty() && System.currentTimeMillis() - timestamps.first() >= periodMs) {
                    timestamps.removeFirst()
                }
            }
            timestamps.addLast(System.currentTimeMillis())
        }
        return chain.proceed(request)
    }
}

@JvmOverloads
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val duration = unit.toMillis(period).milliseconds
    return addInterceptor(RateLimitInterceptor(permits = permits, period = duration))
}

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(permits = permits, period = period))

@JvmOverloads
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val host = url.toHttpUrlOrNull()?.host
    val duration = unit.toMillis(period).milliseconds
    return addInterceptor(RateLimitInterceptor(host = host, permits = permits, period = duration))
}

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder {
    val host = url.toHttpUrlOrNull()?.host
    return addInterceptor(RateLimitInterceptor(host = host, permits = permits, period = period))
}
