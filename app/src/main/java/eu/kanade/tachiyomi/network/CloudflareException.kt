package eu.kanade.tachiyomi.network

import okhttp3.Request
import java.io.IOException

/**
 * Thrown when a Cloudflare challenge or connection reset blocks a background network call.
 * Callers should surface this per-source with a manual "Solve in WebView" action — never
 * auto-launch [com.radiant.webtoon.ui.extensions.CloudflareBypassActivity].
 */
class CloudflareException(
    message: String,
    val url: String? = null,
    val sourceId: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    companion object {
        fun forRequest(request: Request, sourceId: String? = null, cause: Throwable? = null): CloudflareException {
            val host = request.url.host
            return CloudflareException(
                message = "Cloudflare verification required for $host",
                url = request.url.toString(),
                sourceId = sourceId,
                cause = cause,
            )
        }

        fun unwrap(throwable: Throwable): CloudflareException? {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is CloudflareException) return current
                current = current.cause
            }
            return null
        }
    }
}
