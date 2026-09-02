package com.radiant.webtoon.ui.webview

import java.net.URI

/**
 * Normalizes URLs before loading in WebView for manual Cloudflare clearance.
 * API and JSON endpoints cannot show the challenge page — redirect to site origin instead.
 */
object WebViewUrlNormalizer {

    fun normalizeForCloudflareClearance(url: String): String {
        val needsHomepage = url.contains("/api/", ignoreCase = true) ||
            url.endsWith(".json", ignoreCase = true)
        if (!needsHomepage) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val scheme = uri.scheme ?: return url
        val host = uri.host ?: return url
        val port = uri.port
        return if (port != -1) "$scheme://$host:$port/" else "$scheme://$host/"
    }
}
