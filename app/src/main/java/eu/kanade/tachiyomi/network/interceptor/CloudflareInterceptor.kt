package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar? = null
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only intercept if Cloudflare is blocking us with a 403 or 503
        if (response.code in listOf(403, 503) &&
            response.header("Server")?.startsWith("cloudflare", ignoreCase = true) == true
        ) {
            response.close()
            // Serialize WebView bypass — parallel global search must not spawn many WebViews
            return runBlocking {
                bypassMutex.withLock {
                    resolveWithWebView(request, chain)
                }
            }
        }

        return response
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request, chain: Interceptor.Chain): Response {
        // Already cleared? Skip WebView and retry immediately
        val existing = try {
            CookieManager.getInstance().getCookie(request.url.toString())
        } catch (_: Exception) {
            null
        }
        if (!existing.isNullOrBlank() && existing.contains("cf_clearance")) {
            syncWebViewCookiesToJar(request.url.toString(), existing)
            return chain.proceed(request.newBuilder().build())
        }

        val latch = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView?>(null)
        val setupError = AtomicReference<Exception?>(null)

        try {
            mainHandler.post {
                try {
                    val webView = WebView(context.applicationContext)
                    webViewRef.set(webView)

                    val userAgent = request.header("User-Agent")
                        ?: WebSettings.getDefaultUserAgent(context)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
                        databaseEnabled = true
                        @Suppress("DEPRECATION")
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            val cookies = CookieManager.getInstance().getCookie(url)
                            if (!cookies.isNullOrBlank() && cookies.contains("cf_clearance")) {
                                syncWebViewCookiesToJar(url, cookies)
                                latch.countDown()
                            }
                        }
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.loadUrl(request.url.toString())
                } catch (e: Exception) {
                    setupError.set(e)
                    latch.countDown()
                }
            }

            val resolved = latch.await(25, TimeUnit.SECONDS)
            setupError.get()?.let { throw it }

            if (!resolved) {
                throw IOException("Cloudflare bypass timed out for ${request.url}")
            }
        } catch (e: Exception) {
            throw IOException("Failed to bypass Cloudflare: ${e.message}", e)
        } finally {
            val destroyLatch = CountDownLatch(1)
            mainHandler.post {
                try {
                    webViewRef.get()?.apply {
                        stopLoading()
                        destroy()
                    }
                } finally {
                    destroyLatch.countDown()
                }
            }
            destroyLatch.await(5, TimeUnit.SECONDS)
        }

        return chain.proceed(request.newBuilder().build())
    }

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
        } catch (_: Exception) {
            // Best-effort cookie bridge
        }
    }

    companion object {
        private val bypassMutex = Mutex()
    }
}
