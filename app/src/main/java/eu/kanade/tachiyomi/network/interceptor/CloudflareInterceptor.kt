package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            val response = chain.proceed(request)

            // Only intercept if Cloudflare is blocking us with a 403 or 503
            if (response.code in listOf(403, 503) &&
                response.header("Server")?.startsWith("cloudflare", ignoreCase = true) == true
            ) {
                response.close()
                return resolveWithWebView(request, chain)
            }

            return response
        } catch (e: Exception) {
            // Prevent app crash on timeout
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Cloudflare bypass failed or timed out: ${e.message}")
                .body("".toResponseBody(null))
                .build()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request, chain: Interceptor.Chain): Response = runBlocking {
        val latch = CountDownLatch(1)
        var webViewRef: WebView? = null

        try {
            withContext(Dispatchers.Main) {
                val webView = WebView(context.applicationContext)
                webViewRef = webView

                // Mirror the request User-Agent so Cloudflare sees the same agent OkHttp sends
                val userAgent = request.header("User-Agent")
                    ?: WebSettings.getDefaultUserAgent(context)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    databaseEnabled = true
                    // Allow mixed content — some CF challenges load resources over HTTP
                    @Suppress("DEPRECATION")
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)

                        // Check if cf_clearance cookie is now present
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrBlank() && cookies.contains("cf_clearance")) {
                            // Bridge WebView cookies into our AndroidCookieJar
                            syncWebViewCookiesToJar(url, cookies)
                            latch.countDown()
                        }
                    }
                }

                // Ensure the system CookieManager is synced with WebView
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.loadUrl(request.url.toString())
            }

            // Wait up to 5 seconds for Cloudflare challenge to resolve
            val resolved = latch.await(5, TimeUnit.SECONDS)

            if (!resolved) {
                throw java.io.IOException("Cloudflare bypass timed out for ${request.url}")
            }
        } catch (e: Exception) {
            throw java.io.IOException("Failed to bypass Cloudflare: ${e.message}", e)
        } finally {
            // Cleanup WebView on Main thread
            withContext(Dispatchers.Main) {
                webViewRef?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }

        // Retry the original request — AndroidCookieJar now holds cf_clearance
        val newRequest = request.newBuilder().build()
        chain.proceed(newRequest)
    }

    /**
     * Parses raw cookie strings from the WebView's CookieManager and saves them
     * into the AndroidCookieJar so that OkHttp picks them up on the retry.
     */
    private fun syncWebViewCookiesToJar(urlString: String, rawCookies: String) {
        if (cookieJar == null) return

        try {
            val httpUrl = urlString.toHttpUrl()
            val cookies = rawCookies.split("; ").mapNotNull { part ->
                Cookie.parse(httpUrl, part.trim())
            }
            if (cookies.isNotEmpty()) {
                cookieJar.saveFromResponse(httpUrl, cookies)
            }
        } catch (e: Exception) {
            // Best-effort — if cookie bridging fails the retry may still work
            // via the system CookieManager if using WebViewCookieJar
        }
    }
}
