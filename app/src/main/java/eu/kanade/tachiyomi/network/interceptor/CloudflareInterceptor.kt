package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.radiant.webtoon.ui.extensions.CloudflareBypassActivity
import com.radiant.webtoon.ui.webview.WebViewUrlNormalizer
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.CloudflareException
import eu.kanade.tachiyomi.network.UserAgentInterceptor
import eu.kanade.tachiyomi.network.WebViewUtil
import eu.kanade.tachiyomi.network.WebViewUtil.toWebViewHeaders
import eu.kanade.tachiyomi.network.isOutdated
import eu.kanade.tachiyomi.network.setDefaultSettings
import eu.kanade.tachiyomi.network.setUserAgent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Solves Cloudflare challenges the way Mihon does: when a response is a challenge page, a
 * hidden WebView loads the same URL with the same User-Agent, waits for `cf_clearance`, and the
 * OkHttp call is retried transparently. Requests to one host share a lock so a burst of page
 * loads opens a single WebView instead of one per image.
 *
 * Only when the headless solve fails (Turnstile that needs a tap, outdated WebView, renderer
 * crash) does the call fail with [CloudflareException]; the UI then offers the visible sheet via
 * [launchVisibleBypass].
 */
class CloudflareInterceptor(
    context: Context,
    private val cookieJar: AndroidCookieJar? = null,
    private val userAgentInterceptor: UserAgentInterceptor? = null,
) : Interceptor {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                radiant?.postMessage(data.event);
            }
        })
    """.trimIndent()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val lock = hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock() }

        val (response, clearance) = lock.read {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (ConnectionResetRetryInterceptor.isConnectionReset(e)) {
                    throw CloudflareException.forRequest(request, cause = e)
                }
                throw e
            }
            if (!CloudflareChallengeDetector.isChallengeResponse(response)) return response
            response to clearance(request.url)
        }

        lock.write {
            if (isCleared(request.url, clearance)) {
                response.close()
            } else {
                if (!canUseWebView()) {
                    response.close()
                    throw CloudflareException.forRequest(request)
                }
                solve(request, response, clearance)
            }
        }

        return chain.proceed(request)
    }

    private fun canUseWebView(): Boolean {
        // A WebView solve blocks the calling thread; never do that on main.
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        return WebViewUtil.supportsWebView(appContext)
    }

    private fun clearance(url: HttpUrl): String? =
        cookieJar?.get(url)?.firstOrNull { it.name == "cf_clearance" }?.value

    private fun isCleared(url: HttpUrl, previous: String?): Boolean {
        val current = clearance(url)
        return !current.isNullOrBlank() && current != previous
    }

    private fun solve(request: Request, response: Response, clearance: String?) {
        response.close()
        cookieJar?.remove(request.url, COOKIE_NAMES)
        try {
            resolveWithWebView(request, clearance)
        } catch (e: CloudflareException) {
            throw e
        } catch (e: Exception) {
            throw CloudflareException.forRequest(request, cause = e)
        }
        cookieJar?.syncFromWebView(request.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, originalClearance: String?) {
        val latch = CountDownLatch(1)
        var webView: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var challengeFailed = false
        var needsInteraction = false
        var isWebViewOutdated = false
        var isRenderProcessGone = false

        val origRequestUrl = originalRequest.url.toString()
        // A WebView can only GET. For other methods earn the clearance on the site root instead.
        val challengeUrl = if (originalRequest.method == "GET") {
            origRequestUrl
        } else {
            originalRequest.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }
        val headers = originalRequest.headers.toWebViewHeaders()
        val userAgent = originalRequest.header("User-Agent")
            ?: userAgentInterceptor?.userAgent
            ?: UserAgentInterceptor.FALLBACK_USER_AGENT

        mainHandler.post {
            val view = WebView(appContext).apply {
                setDefaultSettings()
                setUserAgent(userAgent)
            }
            webView = view

            view.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun postMessage(event: String) {
                        when (event) {
                            "interactiveBegin" -> {
                                needsInteraction = true
                                latch.countDown()
                            }
                            "fail" -> {
                                challengeFailed = true
                                latch.countDown()
                            }
                        }
                    }
                },
                "radiant",
            )

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!challengeFailed && isCleared(originalRequest.url, originalClearance)) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }
                    if (url == challengeUrl) {
                        if (!challengeFound) {
                            // First load didn't return a challenge, nothing to solve.
                            latch.countDown()
                        } else {
                            view.evaluateJavascript(listenerScript, null)
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame != true) return
                    val mitigated = errorResponse?.responseHeaders?.get("cf-mitigated") == "challenge"
                    val code = errorResponse?.statusCode ?: 0
                    if (mitigated || code in CloudflareChallengeDetector.CHALLENGE_STATUS_CODES) {
                        challengeFound = true
                    } else {
                        latch.countDown()
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.e(TAG, "WebView renderer gone during Cloudflare bypass (crashed=${detail.didCrash()}) $origRequestUrl")
                    isRenderProcessGone = true
                    latch.countDown()
                    return true
                }
            }

            view.loadUrl(challengeUrl, headers)
        }

        latch.await(30, TimeUnit.SECONDS)

        mainHandler.post {
            webView?.run {
                if (!cloudflareBypassed && !isRenderProcessGone) {
                    isWebViewOutdated = runCatching { isOutdated() }.getOrDefault(false)
                }
                if (!isRenderProcessGone) {
                    removeJavascriptInterface("radiant")
                    stopLoading()
                }
                destroy()
            }
            webView = null
        }

        if (!cloudflareBypassed) {
            cookieJar?.remove(originalRequest.url, COOKIE_NAMES)
            val reason = when {
                needsInteraction -> "needs a tap"
                challengeFailed -> "challenge failed"
                isRenderProcessGone -> "renderer crashed"
                isWebViewOutdated -> "WebView outdated"
                else -> "timed out"
            }
            Log.w(TAG, "Headless Cloudflare solve failed ($reason) for ${originalRequest.url.host}")
            throw CloudflareException.forRequest(originalRequest)
        }
        Log.i(TAG, "Cloudflare clearance obtained for ${originalRequest.url.host}")
    }

    companion object {
        private const val TAG = "CloudflareInterceptor"
        private val COOKIE_NAMES = listOf("cf_clearance")

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

        /**
         * Opens the visible Cloudflare bottom sheet for explicit user-initiated bypass
         * (e.g. reader "Solve in WebView") when the headless solve could not finish.
         */
        fun launchVisibleBypass(
            context: Context,
            url: String,
            name: String? = null,
            userAgent: String? = null,
            referer: String? = null,
            oldClearance: String? = null,
            showToast: Boolean = false,
        ) {
            val loadUrl = WebViewUrlNormalizer.normalizeForCloudflareClearance(url)
            val app = context.applicationContext
            val host = try {
                name ?: loadUrl.toHttpUrlOrNull()?.host ?: loadUrl
            } catch (_: Exception) {
                name ?: loadUrl
            }
            Handler(Looper.getMainLooper()).post {
                val intent = Intent(app, CloudflareBypassActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CloudflareBypassActivity.EXTRA_URL, loadUrl)
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
    }
}
