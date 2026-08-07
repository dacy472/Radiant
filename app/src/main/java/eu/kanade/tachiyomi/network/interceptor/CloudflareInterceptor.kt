package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.radiant.webtoon.ui.extensions.CloudflareBypassActivity
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OkHttp interceptor that detects Cloudflare 403/503 challenge pages, solves them via a
 * headless WebView, and falls back to a visible bypass activity when interaction is required.
 * On success it syncs `cf_clearance` + User-Agent and retries the original request.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar? = null,
    private val userAgentInterceptor: UserAgentInterceptor? = null,
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!CloudflareChallengeDetector.isChallengeResponse(response)) {
            return response
        }

        response.close()
        return runBlocking {
            bypassMutex.withLock {
                resolveChallenge(request, chain)
            }
        }
    }

    private fun resolveChallenge(request: Request, chain: Interceptor.Chain): Response {
        val url = request.url.toString()
        val existingCookies = readCookieHeader(url)
        if (CloudflareChallengeDetector.hasClearanceCookie(existingCookies)) {
            syncCookiesToJar(url, existingCookies)
            return chain.proceed(buildRetryRequest(request, existingCookies, userAgentFor(request)))
        }

        val headless = runHeadlessBypass(request)
        if (headless.success) {
            applyBypassSession(headless.userAgent)
            val cookies = readCookieHeader(url)
            syncCookiesToJar(url, cookies)
            return chain.proceed(buildRetryRequest(request, cookies, headless.userAgent))
        }

        val visible = runVisibleBypass(url)
        if (visible.success) {
            applyBypassSession(visible.userAgent)
            val cookies = readCookieHeader(url)
            syncCookiesToJar(url, cookies)
            return chain.proceed(buildRetryRequest(request, cookies, visible.userAgent))
        }

        throw IOException("Cloudflare challenge could not be cleared for ${request.url}")
    }

  @SuppressLint("SetJavaScriptEnabled")
    private fun runHeadlessBypass(request: Request): CloudflareBypassGate.BypassResult {
        val latch = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView?>(null)
        val resultRef = AtomicReference(CloudflareBypassGate.BypassResult(success = false))
        val setupError = AtomicReference<Exception?>(null)
        val userAgent = userAgentFor(request)

        try {
            mainHandler.post {
                try {
                    val webView = WebView(context.applicationContext)
                    webViewRef.set(webView)
                    configureWebView(webView, userAgent)

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            val cookies = CookieManager.getInstance().getCookie(url)
                            if (CloudflareChallengeDetector.hasClearanceCookie(cookies)) {
                                resultRef.set(
                                    CloudflareBypassGate.BypassResult(
                                        success = true,
                                        userAgent = view.settings.userAgentString,
                                    ),
                                )
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

            val resolved = latch.await(HEADLESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            setupError.get()?.let { throw it }
            if (!resolved) {
                return CloudflareBypassGate.BypassResult(success = false)
            }
            return resultRef.get()
        } catch (e: Exception) {
            return CloudflareBypassGate.BypassResult(success = false)
        } finally {
            destroyWebView(webViewRef.get())
        }
    }

    private fun runVisibleBypass(url: String): CloudflareBypassGate.BypassResult {
        val deferred = CloudflareBypassGate.beginVisibleBypass()
        val intent = Intent(context, CloudflareBypassActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CloudflareBypassActivity.EXTRA_URL, url)
            putExtra(CloudflareBypassActivity.EXTRA_NAME, url.toHttpUrl().host)
        }
        context.startActivity(intent)

        return try {
            runBlocking {
                withTimeout(VISIBLE_TIMEOUT_MS) {
                    deferred.await()
                }
            }
        } catch (_: Exception) {
            CloudflareBypassGate.cancelPending()
            CloudflareBypassGate.BypassResult(success = false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, userAgent: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            databaseEnabled = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
    }

    private fun destroyWebView(webView: WebView?) {
        if (webView == null) return
        val destroyLatch = CountDownLatch(1)
        mainHandler.post {
            try {
                webView.stopLoading()
                webView.destroy()
            } finally {
                destroyLatch.countDown()
            }
        }
        destroyLatch.await(5, TimeUnit.SECONDS)
    }

    private fun userAgentFor(request: Request): String {
        return request.header("User-Agent")
            ?: userAgentInterceptor?.userAgent
            ?: WebSettings.getDefaultUserAgent(context)
    }

    private fun applyBypassSession(userAgent: String?) {
        if (!userAgent.isNullOrBlank()) {
            userAgentInterceptor?.userAgent = userAgent
        }
    }

    private fun readCookieHeader(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRetryRequest(
        original: Request,
        cookieHeader: String?,
        userAgent: String?,
    ): Request {
        val builder = original.newBuilder()
        if (!userAgent.isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }
        if (!cookieHeader.isNullOrBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        return builder.build()
    }

    private fun syncCookiesToJar(urlString: String, rawCookies: String?) {
        if (cookieJar == null || rawCookies.isNullOrBlank()) return
        try {
            val httpUrl = urlString.toHttpUrl()
            val cookies = rawCookies.split("; ")
                .mapNotNull { part -> Cookie.parse(httpUrl, part.trim()) }
            if (cookies.isNotEmpty()) {
                cookieJar.saveFromResponse(httpUrl, cookies)
            }
        } catch (_: Exception) {
            // Best-effort cookie bridge
        }
    }

    companion object {
        private val bypassMutex = Mutex()
        private const val HEADLESS_TIMEOUT_SECONDS = 25L
        private const val VISIBLE_TIMEOUT_MS = 120_000L
    }
}
