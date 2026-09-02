package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.CookieJar

/**
 * Alias for the CookieManager-backed jar. Prefer [AndroidCookieJar].
 */
@Deprecated(
    message = "Use AndroidCookieJar — it syncs with android.webkit.CookieManager",
    replaceWith = ReplaceWith("AndroidCookieJar(context)"),
)
class WebViewCookieJar(context: Context) : CookieJar by AndroidCookieJar(context)
