@file:Keep
package eu.kanade.tachiyomi.network.interceptor

import androidx.annotation.Keep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OkHttp interceptor that paces outbound calls so a host cannot burst past Cloudflare
 * (or similar) "too many requests" limits.
 *
 * When [host] is null, each request domain gets its own token bucket (3 req/s per CDN
 * by default on the app client). When [host] is set, only that host is limited — the
 * contract Keiyoushi/Mihon extensions expect from `rateLimit()` / `rateLimitHost()`.
 *
 * Implementation is a token bucket: burst up to [permits], then refill evenly across
 * [period]. Waiters queue on a per-bucket [Mutex] and [delay] rather than holding a
 * lock across [Thread.sleep].
 */
class RateLimitInterceptor(
    private val host: String? = null,
    private val permits: Int,
    private val period: Duration,
) : Interceptor {

    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestHost = request.url.host
        if (host != null && !requestHost.equals(host, ignoreCase = true)) {
            return chain.proceed(request)
        }
        val bucketKey = host ?: requestHost
        buckets.getOrPut(bucketKey) { TokenBucket(permits, period) }.acquire()
        return chain.proceed(request)
    }
}

/**
 * Caps in-flight OkHttp calls per hostname so chapter CDNs cannot open more than
 * [maxConcurrentPerHost] sockets at once (WAF concurrent-connection limits).
 * Complements [RateLimitInterceptor]'s token-bucket (requests per second).
 */
class PerHostConcurrencyInterceptor(
    private val maxConcurrentPerHost: Int = 3,
) : Interceptor {

    private val gates = ConcurrentHashMap<String, Semaphore>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val gate = gates.getOrPut(host) { Semaphore(maxConcurrentPerHost) }
        runBlocking { gate.acquire() }
        try {
            return chain.proceed(chain.request())
        } finally {
            gate.release()
        }
    }
}

/**
 * Token bucket: [capacity] tokens, refilled at [capacity] tokens per [period].
 * [acquire] blocks (via coroutine delay) until one token is available.
 */
internal class TokenBucket(
    private val capacity: Int,
    private val period: Duration,
) {
    private val mutex = Mutex()
    private var tokens = capacity.toDouble()
    private var lastRefillNs = System.nanoTime()
    private val periodNs = period.inWholeNanoseconds.coerceAtLeast(1L)

    fun acquire() {
        runBlocking {
            while (true) {
                val waitMs = mutex.withLock {
                    refillLocked()
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        0L
                    } else {
                        val missing = 1.0 - tokens
                        val waitNs = (missing * periodNs / capacity).toLong()
                        (waitNs / 1_000_000L).coerceAtLeast(1L)
                    }
                }
                if (waitMs <= 0L) return@runBlocking
                delay(waitMs)
            }
        }
    }

    private fun refillLocked() {
        val now = System.nanoTime()
        val elapsed = now - lastRefillNs
        if (elapsed <= 0L) return
        lastRefillNs = now
        val refill = elapsed.toDouble() * capacity / periodNs
        tokens = (tokens + refill).coerceAtMost(capacity.toDouble())
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

fun OkHttpClient.Builder.limitConcurrentRequestsPerHost(
    maxConcurrentPerHost: Int = 3,
): OkHttpClient.Builder = addInterceptor(PerHostConcurrencyInterceptor(maxConcurrentPerHost))

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
