package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketException

/**
 * Retries idempotent requests once when the peer resets the TCP/HTTP2 connection.
 * Cloudflare and similar CDNs often RST OkHttp HTTP/2 sessions on first contact.
 */
class ConnectionResetRetryInterceptor(
    private val maxAttempts: Int = 2,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val retryable = request.method == "GET" || request.method == "HEAD" || request.method == "OPTIONS"
        var lastError: IOException? = null
        val attempts = if (retryable) maxAttempts else 1
        repeat(attempts) { attempt ->
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                lastError = e
                if (!retryable || !isConnectionReset(e) || attempt == attempts - 1) {
                    throw e
                }
                try {
                    Thread.sleep(350L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastError ?: IOException("Connection reset")
    }

    companion object {
        fun isConnectionReset(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is SocketException) {
                    val msg = current.message.orEmpty()
                    if (msg.contains("reset", ignoreCase = true) ||
                        msg.contains("broken pipe", ignoreCase = true) ||
                        msg.contains("connection abort", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                val name = current.javaClass.name
                if (name.contains("StreamResetException") || name.contains("ConnectionShutdownException")) {
                    return true
                }
                val msg = current.message.orEmpty()
                if (msg.contains("CONNECTION_RESET", ignoreCase = true) ||
                    msg.contains("ERR_CONNECTION_RESET", ignoreCase = true) ||
                    msg.contains("stream was reset", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
