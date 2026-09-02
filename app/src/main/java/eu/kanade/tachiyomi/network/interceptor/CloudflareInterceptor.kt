package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.radiant.webtoon.ui.extensions.CloudflareBypassActivity
import com.radiant.webtoon.ui.webview.WebViewUrlNormalizer
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.CloudflareException
import eu.kanade.tachiyomi.network.UserAgentInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Detects Cloudflare 403/503 challenges and connection resets on the OkHttp call thread.
 *
 * Background/catalogue/search calls fail fast with [CloudflareException] so UI can show a
 * per-source inline message. Manual bypass is only via [launchVisibleBypass] (reader button,
 * explicit user action).
 */
class CloudflareInterceptor(
    private val context: Context,
    @Suppress("unused") private val cookieJar: AndroidCookieJar? = null,
    @Suppress("unused") private val userAgentInterceptor: UserAgentInterceptor? = null,
) : Interceptor {

    private val appContext = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (ConnectionResetRetryInterceptor.isConnectionReset(e)) {
                throw CloudflareException.forRequest(request, cause = e)
            }
            throw e
        }
        if (!CloudflareChallengeDetector.isChallengeResponse(response)) {
            return response
        }
        response.close()
        throw CloudflareException.forRequest(request)
    }

    companion object {
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
         * (e.g. reader "Solve in WebView"). Never called automatically from intercept.
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
