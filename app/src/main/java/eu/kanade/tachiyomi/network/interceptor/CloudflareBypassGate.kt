package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates a visible Cloudflare bottom sheet with a blocking OkHttp interceptor.
 */
object CloudflareBypassGate {

    data class BypassResult(
        val success: Boolean,
        val userAgent: String? = null,
        val cookies: String? = null,
    )

    private val pending = AtomicReference<CompletableDeferred<BypassResult>?>(null)

    fun beginVisibleBypass(): CompletableDeferred<BypassResult> {
        val deferred = CompletableDeferred<BypassResult>()
        if (!pending.compareAndSet(null, deferred)) {
            deferred.complete(BypassResult(success = false))
            return deferred
        }
        return deferred
    }

    fun complete(result: BypassResult) {
        pending.getAndSet(null)?.complete(result)
    }

    fun cancelPending() {
        pending.getAndSet(null)?.complete(BypassResult(success = false))
    }
}
