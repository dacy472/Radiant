package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Response

/**
 * Detects Cloudflare anti-bot challenge responses that should trigger a WebView bypass.
 */
internal object CloudflareChallengeDetector {

    val CHALLENGE_STATUS_CODES = setOf(403, 503, 429)

    private val HTML_MARKERS = listOf(
        "cf-browser-verification",
        "challenge-platform",
        "cf-challenge",
        "cf-turnstile",
        "Just a moment",
        "Enable JavaScript and cookies to continue",
        "Checking your browser",
        "Attention Required! | Cloudflare",
        "/cdn-cgi/challenge-platform/",
    )

    fun isChallengeResponse(response: Response): Boolean {
        if (response.code !in CHALLENGE_STATUS_CODES) return false
        if (isCloudflareHeader(response)) return true
        return peekChallengeHtml(response)
    }

    fun peekChallengeHtml(response: Response): Boolean {
        return response.peekBody(CHALLENGE_PEEK_BYTES).use { body ->
            isChallengeHtml(body.string())
        }
    }

    fun isChallengeHtml(snippet: String): Boolean {
        return HTML_MARKERS.any { marker -> snippet.contains(marker, ignoreCase = true) }
    }

    fun isCloudflareHeader(response: Response): Boolean {
        if (response.header("cf-ray") != null) return true
        if (response.header("Server")?.contains("cloudflare", ignoreCase = true) == true) {
            return true
        }
        return response.headers("Set-Cookie").any { cookie ->
            cookie.contains("__cf_bm", ignoreCase = true) ||
                cookie.contains("cf_clearance", ignoreCase = true)
        }
    }

    fun hasClearanceCookie(rawCookies: String?): Boolean {
        return !rawCookies.isNullOrBlank() && rawCookies.contains("cf_clearance")
    }

    private const val CHALLENGE_PEEK_BYTES = 64L * 1024L
}
