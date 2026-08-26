package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.radiant.webtoon.ui.extensions.CloudflareBypassActivity
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.UserAgentInterceptor
import eu.kanade.tachiyomi.network.WebViewUserAgentProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pauses the OkHttp call thread on a Cloudflare 403/503 challenge, solves it in WebView,
 * then retries the same request with `cf_clearance` + User-Agent.
 *
 * 1. Invisible WebView (auto JS / managed challenges).
 * 2. Visible bottom sheet if the challenge needs a tap (Turnstile).
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar? = null,
    private val userAgentInterceptor: UserAgentInterceptor? = null,
) : Interceptor {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (ConnectionResetRetryInterceptor.isConnectionReset(e)) {
                // CDNs often RST OkHttp before returning a 403 challenge page.
                return runBlocking {
                    bypassMutex.withLock {
                        resolveChallenge(request, chain)
                    }
                }
            }
            throw e
        }
        val challenge = CloudflareChallengeDetector.isChallengeResponse(response)
        if (!challenge) {
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
        // Always the challenged request URL (CDN image or manga page) — never source.baseUrl.
        val url = request.url.toString()
        val oldClearance = CloudflareChallengeDetector.clearanceValue(readCookieHeader(url))
        cookieJar?.remove(request.url, listOf("cf_clearance"))

        val solved = runInvisibleBypass(request, oldClearance)
            ?: runVisibleBypass(request, oldClearance)

        if (solved == null || !solved.success) {
            throw IOException(
                "Cloudflare challenge could not be cleared for ${request.url}. " +
                    "Solve the challenge, then reload.",
            )
        }

        applyBypassSession(solved.userAgent)
        flushCookieManager()
        val cookies = cookiesForUrl(url, solved.cookies)
        persistCookiesForRequestUrl(url, cookies)
        return chain.proceed(buildRetryRequest(request, cookies, solved.userAgent))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runInvisibleBypass(
        request: Request,
        oldClearance: String?,
    ): CloudflareBypassGate.BypassResult? {
        val latch = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView?>(null)
        val attachedParent = AtomicReference<ViewGroup?>(null)
        val resultRef = AtomicReference<CloudflareBypassGate.BypassResult?>(null)
        val finished = AtomicBoolean(false)

        fun succeed(view: WebView?, cookies: String?) {
            if (!finished.compareAndSet(false, true)) return
            resultRef.set(
                CloudflareBypassGate.BypassResult(
                    success = true,
                    userAgent = view?.settings?.userAgentString ?: userAgentFor(request),
                    cookies = cookies,
                ),
            )
            latch.countDown()
        }

        mainHandler.post {
            try {
                val host = ForegroundActivityHolder.activity ?: appContext
                val webView = WebView(host)
                webViewRef.set(webView)
                configureWebView(webView, request)
                sizeWebView(webView)

                val activity = ForegroundActivityHolder.activity
                if (activity != null) {
                    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                    val params = FrameLayout.LayoutParams(
                        webView.layoutParams.width,
                        webView.layoutParams.height,
                    ).apply { leftMargin = webView.layoutParams.width }
                    parent.addView(webView, params)
                    attachedParent.set(parent)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        val challengeUrl = request.url.toString()
                        val cookies = cookiesForUrl(
                            challengeUrl,
                            CookieManager.getInstance().getCookie(finishedUrl),
                        )
                        if (CloudflareChallengeDetector.hasNewClearance(cookies, oldClearance)) {
                            succeed(view, cookies)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        req: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (!req.isForMainFrame) return
                        if (errorResponse.statusCode in CloudflareChallengeDetector.CHALLENGE_STATUS_CODES) {
                            return
                        }
                        val cookies = CookieManager.getInstance().getCookie(request.url.toString())
                        if (CloudflareChallengeDetector.hasNewClearance(cookies, oldClearance)) {
                            succeed(view, cookies)
                        }
                    }
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.loadUrl(request.url.toString(), challengeLoadHeaders(request))
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(INVISIBLE_TIMEOUT_SECONDS)
        while (SystemClock.elapsedRealtime() < deadline && !finished.get()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            latch.await(minOf(400L, remaining), TimeUnit.MILLISECONDS)
            if (finished.get()) break
            val cookies = readCookieHeader(request.url.toString())
            if (CloudflareChallengeDetector.hasNewClearance(cookies, oldClearance)) {
                succeed(webViewRef.get(), cookies)
                break
            }
        }

        val result = resultRef.get()
        destroyWebView(webViewRef.get(), attachedParent.get())
        return result?.takeIf { it.success }
    }

    private fun runVisibleBypass(
        request: Request,
        oldClearance: String?,
    ): CloudflareBypassGate.BypassResult? {
        val deferred = CloudflareBypassGate.beginVisibleBypass()
        launchVisibleBypass(
            context = appContext,
            url = request.url.toString(),
            userAgent = userAgentFor(request),
            referer = challengeRefererFor(request),
            oldClearance = oldClearance,
            showToast = false,
        )
        return try {
            runBlocking {
                withTimeout(VISIBLE_TIMEOUT_MS) {
                    deferred.await()
                }
            }.takeIf { it.success }
        } catch (_: Exception) {
            CloudflareBypassGate.cancelPending()
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, request: Request) {
        val ua = userAgentFor(request)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = ua
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun sizeWebView(webView: WebView) {
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(360)
        val height = metrics.heightPixels.coerceAtLeast(640)
        webView.layoutParams = ViewGroup.LayoutParams(width, height)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
    }

    private fun destroyWebView(webView: WebView?, parent: ViewGroup?) {
        if (webView == null) return
        val destroyLatch = CountDownLatch(1)
        mainHandler.post {
            try {
                parent?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
            } finally {
                destroyLatch.countDown()
            }
        }
        destroyLatch.await(5, TimeUnit.SECONDS)
    }

    private fun userAgentFor(@Suppress("UNUSED_PARAMETER") request: Request): String {
        return userAgentInterceptor?.userAgent?.takeIf { it.isNotBlank() }
            ?: WebViewUserAgentProvider.getDefault(appContext)
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

    private fun cookiesForUrl(url: String, fallback: String?): String? {
        val fromUrl = readCookieHeader(url)
        if (CloudflareChallengeDetector.hasClearanceCookie(fromUrl)) return fromUrl
        url.toHttpUrlOrNull()?.let { http ->
            val hostOrigin = "${http.scheme}://${http.host}/"
            val fromHost = readCookieHeader(hostOrigin)
            if (CloudflareChallengeDetector.hasClearanceCookie(fromHost)) return fromHost
        }
        return fromUrl?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
    }

    private fun persistCookiesForRequestUrl(url: String, rawCookies: String?) {
        syncCookiesToJar(url, rawCookies)
        url.toHttpUrlOrNull()?.let { http ->
            val hostOrigin = "${http.scheme}://${http.host}/"
            if (hostOrigin != url) {
                syncCookiesToJar(hostOrigin, rawCookies ?: readCookieHeader(hostOrigin))
            }
        }
    }

    private fun flushCookieManager() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // Best-effort flush before OkHttp retry
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
            cookieJar.syncFromWebView(urlString.toHttpUrl(), rawCookies)
        } catch (_: Exception) {
            // Best-effort cookie bridge
        }
    }

    private fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    /**
     * WebView [android.webkit.WebView.loadUrl] extra headers: original OkHttp Referer
     * (manga site for CDN images) + the same User-Agent OkHttp uses.
     */
    private fun challengeLoadHeaders(request: Request): MutableMap<String, String> {
        val headers = parseHeaders(request.headers).toMutableMap()
        headers.keys.filter {
            it.equals("User-Agent", ignoreCase = true) || it.equals("Referer", ignoreCase = true)
        }.forEach { headers.remove(it) }
        headers["User-Agent"] = userAgentFor(request)
        val referer = challengeRefererFor(request)
        if (referer.isNotBlank()) {
            headers["Referer"] = referer
        }
        return headers
    }

    companion object {
        private val bypassMutex = Mutex()
        private const val INVISIBLE_TIMEOUT_SECONDS = 30L
        private const val VISIBLE_TIMEOUT_MS = 180_000L

        const val TOAST_MESSAGE = "Solve the Cloudflare challenge to continue"

        /**
         * Referer sent to the challenge WebView.
         *
         * For CDN image challenges this is the original OkHttp/Coil `Referer` (manga site),
         * not the CDN host. If the request has no Referer, fall back to the request origin.
         */
        fun challengeRefererFor(request: Request): String {
            request.header("Referer")?.takeIf { it.isNotBlank() }?.let { return it }
            return originReferer(request.url.toString())
        }

        fun originRefererFor(request: Request): String = challengeRefererFor(request)

        /** Origin site URL with trailing slash (`https://host/`), never a CDN path. */
        fun originReferer(url: String): String {
            val httpUrl = url.toHttpUrlOrNull() ?: return ""
            val defaultPort = if (httpUrl.scheme == "https") 443 else 80
            val port = if (httpUrl.port != defaultPort) ":${httpUrl.port}" else ""
            return "${httpUrl.scheme}://${httpUrl.host}$port/"
        }

        fun launchVisibleBypass(
            context: Context,
            url: String,
            name: String? = null,
            userAgent: String? = null,
            referer: String? = null,
            oldClearance: String? = null,
            showToast: Boolean = false,
        ) {
            val app = context.applicationContext
            val host = try {
                name ?: url.toHttpUrl().host
            } catch (_: Exception) {
                name ?: url
            }
            Handler(Looper.getMainLooper()).post {
                val intent = Intent(app, CloudflareBypassActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CloudflareBypassActivity.EXTRA_URL, url)
                    putExtra(CloudflareBypassActivity.EXTRA_NAME, host)
                    putExtra(CloudflareBypassActivity.EXTRA_USER_AGENT, userAgent)
                    referer?.takeIf { it.isNotBlank() }?.let { value ->
                        putExtra(CloudflareBypassActivity.EXTRA_REFERER, value)
                    }
                    putExtra(CloudflareBypassActivity.EXTRA_OLD_CLEARANCE, oldClearance)
                    putExtra(CloudflareBypassActivity.EXTRA_SHOW_TOAST, showToast)
                }
                app.startActivity(intent)
            }
        }

        private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
            val name = _name.lowercase(Locale.ENGLISH)
            val value = _value.lowercase(Locale.ENGLISH)
            if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
            if (name == "connection" && value == "upgrade") return false
            return true
        }

        private val UNSAFE_HEADER_NAMES = listOf(
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
}
