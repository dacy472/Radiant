package eu.kanade.tachiyomi.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.Headers
import java.util.Locale

/**
 * WebView helpers shared by the headless Cloudflare solver and the visible bypass sheet.
 * Mirrors Mihon's `WebViewUtil` so the browser that earns `cf_clearance` looks exactly like
 * the OkHttp client that will later present it.
 */
object WebViewUtil {
    private const val TAG = "WebViewUtil"
    const val MINIMUM_WEBVIEW_VERSION = 118

    /**
     * Turns the stock WebView UA (`…; Pixel 7 Build/…; wv) … Version/4.0 Chrome/…`) into the
     * reduced Chrome-on-Android form Cloudflare expects from a real browser.
     */
    fun inferChromeUserAgent(webViewUserAgent: String): String {
        return webViewUserAgent
            .replace("; Android .*?\\)".toRegex(), "; Android 10; K)")
            .replace("Version/.* Chrome/".toRegex(), "Chrome/")
    }

    fun supportsWebView(context: Context): Boolean {
        try {
            CookieManager.getInstance()
        } catch (e: Throwable) {
            Log.e(TAG, "WebView unavailable", e)
            return false
        }
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
    }

    fun Headers.toWebViewHeaders(): Map<String, String> {
        return this
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    private fun isRequestHeaderSafe(rawName: String, rawValue: String): Boolean {
        val name = rawName.lowercase(Locale.ENGLISH)
        val value = rawValue.lowercase(Locale.ENGLISH)
        if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
        if (name == "connection" && value == "upgrade") return false
        return true
    }

    private val UNSAFE_HEADER_NAMES = setOf(
        "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive",
        "transfer-encoding", "set-cookie", "origin",
    )
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT
        setSupportMultipleWindows(true)
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/**
 * Sets the UA string and the matching `Sec-CH-UA` metadata. Without the metadata Chromium keeps
 * advertising "Android WebView" brands that contradict the spoofed Chrome UA, which is one of the
 * signals Cloudflare uses to fail the challenge silently.
 */
fun WebView.setUserAgent(userAgent: String) {
    settings.userAgentString = userAgent
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val match = CHROME_VERSION_REGEX.find(userAgent) ?: return
    val majorVersion = match.groupValues[1]
    val fullVersion = majorVersion + match.groupValues[2].ifEmpty { ".0.0.0" }
    try {
        val metadata = WebSettingsCompat.getUserAgentMetadata(settings)
        val brands = metadata.brandVersionList.map { brandVersion ->
            val brand = when (brandVersion.brand) {
                WEBVIEW_BRAND -> CHROME_BRAND
                CHROMIUM_BRAND -> CHROMIUM_BRAND
                else -> return@map brandVersion
            }
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand)
                .setMajorVersion(majorVersion)
                .setFullVersion(fullVersion)
                .build()
        }
        WebSettingsCompat.setUserAgentMetadata(
            settings,
            UserAgentMetadata.Builder(metadata)
                .setBrandVersionList(brands)
                .setFullVersion(fullVersion)
                .build(),
        )
    } catch (e: Exception) {
        Log.w("WebViewUtil", "Failed to set user agent metadata", e)
    }
}

fun WebView.isOutdated(): Boolean = webViewMajorVersion() < WebViewUtil.MINIMUM_WEBVIEW_VERSION

private fun WebView.webViewMajorVersion(): Int {
    val original = settings.userAgentString
    settings.userAgentString = null
    val default = settings.userAgentString
    settings.userAgentString = original
    return """.*Chrome/(\d+)\..*""".toRegex().matchEntire(default)
        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

private const val WEBVIEW_BRAND = "Android WebView"
private const val CHROMIUM_BRAND = "Chromium"
private const val CHROME_BRAND = "Google Chrome"
private val CHROME_VERSION_REGEX = """Chrome/(\d+)(\.[\d.]+)?""".toRegex()
