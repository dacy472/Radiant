package eu.kanade.tachiyomi.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Routes WebView GET subresource loads through OkHttp (DoH DNS, shared cookies/UA).
 * Used during Cloudflare bypass so challenge assets resolve when system DNS is blocked.
 */
object WebViewResourceFetcher {

    fun interceptGet(
        client: OkHttpClient,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url?.toString()?.takeIf { it.startsWith("http") } ?: return null

        return try {
            val okRequest = Request.Builder()
                .url(url)
                .get()
                .apply {
                    request.requestHeaders.forEach { (name, value) ->
                        if (isHeaderSafe(name, value)) {
                            header(name, value)
                        }
                    }
                }
                .build()

            val response = client.newCall(okRequest).execute()
            val contentType = response.header("Content-Type")
            val mimeType = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: guessMimeType(url)
            val encoding = contentType?.let(::parseCharset)
            val headers = response.headers.toMultimap().mapValues { (_, values) ->
                values.joinToString(", ")
            }
            val reason = response.message.ifBlank {
                when (response.code) {
                    200 -> "OK"
                    else -> ""
                }
            }
            val body = response.body?.byteStream()
                ?: return WebResourceResponse(mimeType, encoding, response.code, reason, headers, null)

            WebResourceResponse(mimeType, encoding, response.code, reason, headers, body)
        } catch (_: Exception) {
            null
        }
    }

    private fun isHeaderSafe(name: String, value: String): Boolean {
        val lowerName = name.lowercase(Locale.ENGLISH)
        val lowerValue = value.lowercase(Locale.ENGLISH)
        if (lowerName in UNSAFE_HEADER_NAMES || lowerName.startsWith("proxy-")) return false
        if (lowerName == "connection" && lowerValue == "upgrade") return false
        return true
    }

    private fun parseCharset(contentType: String): String? {
        return Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
    }

    private fun guessMimeType(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ENGLISH)
        return when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            else -> "text/html"
        }
    }

    private val UNSAFE_HEADER_NAMES = setOf(
        "content-length",
        "host",
        "trailer",
        "te",
        "upgrade",
        "cookie2",
        "keep-alive",
        "transfer-encoding",
        "set-cookie",
    )
}
