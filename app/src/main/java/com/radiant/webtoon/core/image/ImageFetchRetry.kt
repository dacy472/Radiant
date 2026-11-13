package com.radiant.webtoon.core.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Bounded retry for page image downloads: transient network failures, HTTP 429 and 5xx are
 * retried with exponential backoff; other HTTP errors (403, 404, ...) fail immediately so the
 * Cloudflare / error UI shows up without delay.
 */
object ImageFetchRetry {
    const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 800L
    private val HTTP_CODE = Regex("""HTTP(?: error)? (\d{3})""", RegexOption.IGNORE_CASE)

    fun httpCodeOf(error: Throwable): Int? =
        error.message?.let { HTTP_CODE.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    fun isRetryable(error: Throwable): Boolean {
        if (error is CancellationException) return false
        val code = httpCodeOf(error)
        if (code != null) return code == 429 || code in 500..599
        return error is IOException
    }

    fun backoffMs(attempt: Int): Long = BASE_DELAY_MS * (1L shl attempt.coerceIn(0, 4))

    suspend fun <T> run(maxAttempts: Int = MAX_ATTEMPTS, block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: Throwable) {
                if (attempt >= maxAttempts - 1 || !isRetryable(e)) throw e
                delay(backoffMs(attempt))
                attempt++
            }
        }
    }
}
