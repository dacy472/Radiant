package eu.kanade.tachiyomi.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Supplies the device's native WebView default User-Agent via
 * [WebSettings.getDefaultUserAgent] so OkHttp scrapers match the system browser.
 */
object WebViewUserAgentProvider {

    private const val TAG = "WebViewUserAgent"
    private const val MAIN_THREAD_TIMEOUT_MS = 5_000L

    @Volatile
    private var cached: String? = null

    /**
     * Returns [WebSettings.getDefaultUserAgent] for this device, cached for the process lifetime.
     * Always resolved on the main thread (required on some Android versions).
     */
    fun getDefault(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appContext = context.applicationContext
            val userAgent = fetchOnMainThread(appContext)
            cached = userAgent
            return userAgent
        }
    }

    /** Clear cache (tests / rare WebView provider changes). */
    fun clearCache() {
        synchronized(this) { cached = null }
    }

    private fun fetchOnMainThread(context: Context): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return fetchUnsafe(context)
        }
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(fetchUnsafe(context))
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val userAgent = result.get()
        if (completed && !userAgent.isNullOrBlank()) {
            return userAgent
        }
        Log.w(TAG, "Timed out or failed fetching WebView UA on main thread; using fallback")
        return UserAgentInterceptor.FALLBACK_USER_AGENT
    }

    private fun fetchUnsafe(context: Context): String {
        return try {
            // Reduce to the Chrome-on-Android form (drop the "; wv" build marker and
            // "Version/4.0") so Cloudflare scores us like a browser, not an embedded WebView.
            WebViewUtil.inferChromeUserAgent(WebSettings.getDefaultUserAgent(context)).also { ua ->
                Log.i(TAG, "Using inferred Chrome User-Agent (${ua.length} chars)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebSettings.getDefaultUserAgent failed; using fallback", e)
            UserAgentInterceptor.FALLBACK_USER_AGENT
        }
    }
}
