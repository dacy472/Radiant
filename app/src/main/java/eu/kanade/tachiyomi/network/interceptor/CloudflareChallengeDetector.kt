package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Response

/**
 * Detects Cloudflare anti-bot challenge responses that should trigger a WebView bypass.
 *
 * Matches Mihon / existing app behavior:
 * - Official `cf-mitigated: challenge` header (current Mihon), and/or
 * - HTTP 403/503 plus challenge HTML markers (legacy + Mangafire-style pages).
 *
 * A `cf-ray` header alone is not a challenge — almost every Cloudflare-proxied response has one.
 */
internal object CloudflareChallengeDetector {

    val CHALLENGE_STATUS_CODES = setOf(403, 503)

    private val CLOUDFLARE_SERVERS = setOf("cloudflare", "cloudflare-nginx")

    private val HTML_MARKERS = listOf(
        "challenge-error-title",
        "challenge-error-text",
        "cf-browser-verification",
        "challenge-platform",
        "cf-challenge",
        "cf-turnstile",
        "cf_chl_",
        "Just a moment",
        "Enable JavaScript and cookies to continue",
        "Checking your browser",
        "Attention Required! | Cloudflare",
        "/cdn-cgi/challenge-platform/",
    )

    fun isChallengeResponse(response: Response): Boolean {
        // Official Cloudflare signal (Mihon): do not require HTML peek.
        if (isCfMitigatedChallenge(response)) return true
        if (response.code !in CHALLENGE_STATUS_CODES) return false
        return peekChallengeHtml(response)
    }

    /** https://developers.cloudflare.com/cloudflare-challenges/.../detect-response/ */
    fun isCfMitigatedChallenge(response: Response): Boolean {
        return response.header("cf-mitigated").equals("challenge", ignoreCase = true)
    }

    fun peekChallengeHtml(response: Response): Boolean {
        return response.peekBody(CHALLENGE_PEEK_BYTES).use { body ->
            isChallengeHtml(body.string())
        }
    }

    fun isChallengeHtml(snippet: String): Boolean {
        return HTML_MARKERS.any { marker -> snippet.contains(marker, ignoreCase = true) }
    }

    fun isCloudflareServer(response: Response): Boolean {
        val server = response.header("Server").orEmpty()
        return CLOUDFLARE_SERVERS.any { it.equals(server, ignoreCase = true) }
    }

    fun isCloudflareHeader(response: Response): Boolean {
        if (isCloudflareServer(response)) return true
        return response.headers("Set-Cookie").any { cookie ->
            cookie.contains("__cf_bm", ignoreCase = true) ||
                cookie.contains("cf_clearance", ignoreCase = true)
        }
    }

    fun hasClearanceCookie(rawCookies: String?): Boolean {
        return clearanceValue(rawCookies) != null
    }

    fun clearanceValue(rawCookies: String?): String? {
        if (rawCookies.isNullOrBlank()) return null
        return rawCookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    fun hasNewClearance(rawCookies: String?, previousValue: String?): Boolean {
        val current = clearanceValue(rawCookies) ?: return false
        return current != previousValue
    }

    private const val CHALLENGE_PEEK_BYTES = 64L * 1024L
}
