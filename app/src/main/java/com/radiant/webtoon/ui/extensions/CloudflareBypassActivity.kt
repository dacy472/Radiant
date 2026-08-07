package com.radiant.webtoon.ui.extensions

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassGate
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeDetector
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy

/**
 * Visible Cloudflare bypass UI — opened automatically when headless WebView solving fails.
 */
class CloudflareBypassActivity : ComponentActivity() {

    private val networkHelper: NetworkHelper by injectLazy()
    private var webView: WebView? = null
    private var finished = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://google.com"
        val extensionName = intent.getStringExtra(EXTRA_NAME) ?: url

        setContent {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Cloudflare check", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    extensionName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { finishWithResult(success = false) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { webView?.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            Button(
                                onClick = { finishWithResult(success = true) },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Done")
                            }
                        },
                    )
                },
            ) { padding ->
                AndroidViewBinding(padding, url) { wv ->
                    webView = wv
                }
            }
        }
    }

    @Composable
    private fun AndroidViewBinding(
        padding: PaddingValues,
        url: String,
        onWebViewCreated: (WebView) -> Unit,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
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
                        userAgentString = networkHelper.userAgentInterceptor.userAgent
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            val cookies = CookieManager.getInstance().getCookie(finishedUrl ?: url)
                            if (CloudflareChallengeDetector.hasClearanceCookie(cookies)) {
                                finishWithResult(success = true)
                            }
                        }
                    }

                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(url)
                    onWebViewCreated(this)
                }
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        )
    }

    private fun finishWithResult(success: Boolean) {
        if (finished) return
        finished = true

        val url = intent.getStringExtra(EXTRA_URL)
        val userAgent = webView?.settings?.userAgentString
        if (success) {
            if (!userAgent.isNullOrBlank()) {
                networkHelper.userAgentInterceptor.userAgent = userAgent
            }
            if (!url.isNullOrBlank()) {
                syncCookiesToJar(url)
            }
        }

        CloudflareBypassGate.complete(
            CloudflareBypassGate.BypassResult(
                success = success,
                userAgent = userAgent,
            ),
        )
        finish()
    }

    private fun syncCookiesToJar(url: String) {
        try {
            val rawCookies = CookieManager.getInstance().getCookie(url) ?: return
            val httpUrl = url.toHttpUrl()
            val cookies = rawCookies.split("; ")
                .mapNotNull { part -> okhttp3.Cookie.parse(httpUrl, part.trim()) }
            if (cookies.isNotEmpty()) {
                networkHelper.cookieJar.saveFromResponse(httpUrl, cookies)
            }
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
    }
}
