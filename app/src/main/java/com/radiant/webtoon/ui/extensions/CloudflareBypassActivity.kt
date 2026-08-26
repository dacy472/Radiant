package com.radiant.webtoon.ui.extensions

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.radiant.webtoon.ui.theme.WebtoonTheme
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassGate
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeDetector
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy

/**
 * Visible bottom sheet used only when an invisible WebView cannot clear Cloudflare
 * (Turnstile / checkbox). Completes automatically when `cf_clearance` appears so the
 * paused OkHttp call can retry without a manual Done tap.
 */
class CloudflareBypassActivity : ComponentActivity() {

    private val networkHelper: NetworkHelper by injectLazy()
    private var webView: WebView? = null
    private var finished = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://google.com"
        val host = intent.getStringExtra(EXTRA_NAME) ?: url
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            ?: networkHelper.defaultUserAgentProvider()
        val referer = intent.getStringExtra(EXTRA_REFERER)
            ?.takeIf { it.isNotBlank() }
            ?: CloudflareInterceptor.originReferer(url)
        val oldClearance = intent.getStringExtra(EXTRA_OLD_CLEARANCE)
        if (intent.getBooleanExtra(EXTRA_SHOW_TOAST, false)) {
            Toast.makeText(this, CloudflareInterceptor.TOAST_MESSAGE, Toast.LENGTH_LONG).show()
        }

        setContent {
            WebtoonTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { finishWithResult(success = false) },
                    sheetState = sheetState,
                ) {
                    Text(
                        text = "Cloudflare check · $host",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        text = CloudflareInterceptor.TOAST_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    ChallengeWebView(
                        url = url,
                        userAgent = userAgent,
                        referer = referer,
                        oldClearance = oldClearance,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.72f),
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun ChallengeWebView(
        url: String,
        userAgent: String,
        referer: String,
        oldClearance: String?,
        modifier: Modifier = Modifier,
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = userAgent
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            val manager = CookieManager.getInstance()
                            val cookies = manager.getCookie(url)
                                ?: manager.getCookie(finishedUrl ?: url)
                            if (CloudflareChallengeDetector.hasNewClearance(cookies, oldClearance)) {
                                finishWithResult(success = true)
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    val loadHeaders = HashMap<String, String>(2).apply {
                        put("User-Agent", userAgent)
                        if (referer.isNotBlank()) put("Referer", referer)
                    }
                    loadUrl(url, loadHeaders)
                    webView = this
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }

    private fun finishWithResult(success: Boolean) {
        if (finished) return
        finished = true

        val url = intent.getStringExtra(EXTRA_URL)
        val userAgent = webView?.settings?.userAgentString
            ?: intent.getStringExtra(EXTRA_USER_AGENT)
            ?: networkHelper.defaultUserAgentProvider()
        val cookies = url?.let { CookieManager.getInstance().getCookie(it) }
        if (success) {
            networkHelper.userAgentInterceptor.userAgent = userAgent
            if (!url.isNullOrBlank()) {
                syncCookiesToJar(url)
            }
        }

        CloudflareBypassGate.complete(
            CloudflareBypassGate.BypassResult(
                success = success,
                userAgent = userAgent,
                cookies = cookies,
            ),
        )
        finish()
    }

    private fun syncCookiesToJar(url: String) {
        try {
            val rawCookies = CookieManager.getInstance().getCookie(url) ?: return
            networkHelper.cookieJar.syncFromWebView(url.toHttpUrl(), rawCookies)
        } catch (_: Exception) {
            // Best-effort cookie bridge
        }
    }

    override fun onDestroy() {
        if (!finished) {
            CloudflareBypassGate.complete(CloudflareBypassGate.BypassResult(success = false))
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_OLD_CLEARANCE = "old_clearance"
        const val EXTRA_SHOW_TOAST = "show_toast"
    }
}
