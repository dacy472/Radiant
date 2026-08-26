package eu.kanade.tachiyomi.network

import okhttp3.CookieJar

/**
 * Alias for the CookieManager-backed jar. Prefer [AndroidCookieJar].
 */
@Deprecated(
    message = "Use AndroidCookieJar — it syncs with android.webkit.CookieManager",
    replaceWith = ReplaceWith("AndroidCookieJar()"),
)
class WebViewCookieJar : CookieJar by AndroidCookieJar()
