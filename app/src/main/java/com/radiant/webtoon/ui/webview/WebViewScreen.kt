package com.radiant.webtoon.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    url: String,
    headers: Map<String, String> = emptyMap(),
    onBackClick: () -> Unit
) {
    val loadUrl = remember(url) { WebViewUrlNormalizer.normalizeForCloudflareClearance(url) }
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val headerMap = remember(loadUrl, headers) {
        HashMap<String, String>(headers.size + 2).apply {
            headers.forEach { (key, value) ->
                if (value.isNotBlank() && !key.equals("X-Radiant-Source-Id", ignoreCase = true)) {
                    put(key, value)
                }
            }
            if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                val ua = networkHelper.userAgentInterceptor.userAgent
                    .ifBlank { networkHelper.defaultUserAgentProvider() }
                if (ua.isNotBlank()) put("User-Agent", ua)
            }
            if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                val origin = loadUrl.toHttpUrlOrNull()?.let { httpUrl ->
                    val defaultPort = if (httpUrl.scheme == "https") 443 else 80
                    val port = if (httpUrl.port != defaultPort) ":${httpUrl.port}" else ""
                    "${httpUrl.scheme}://${httpUrl.host}$port/"
                }.orEmpty()
                if (origin.isNotBlank()) put("Referer", origin)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.url?.toHttpUrlOrNull()?.let { httpUrl ->
                    networkHelper.cookieJar.syncFromWebView(httpUrl)
                }
            }
            runCatching { CookieManager.getInstance().flush() }
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    val dismiss: () -> Unit = {
        runCatching {
            webViewRef?.url?.toHttpUrlOrNull()?.let { httpUrl ->
                networkHelper.cookieJar.syncFromWebView(httpUrl)
            }
        }
        runCatching { CookieManager.getInstance().flush() }
        onBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        loadUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = dismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pageError = null
                        isLoading = true
                        webViewRef?.reload()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        try {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                        } catch (_: Exception) {
                            // WebView provider may be unavailable on some emulators
                        }

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val next = request?.url?.toString() ?: return false
                                return !(next.startsWith("http://") || next.startsWith("https://"))
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                pageError = null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.toHttpUrlOrNull()?.let { httpUrl ->
                                    runCatching {
                                        Injekt.get<NetworkHelper>().cookieJar.syncFromWebView(httpUrl)
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        error?.description?.toString()
                                    } else {
                                        null
                                    }
                                    pageError = desc ?: "Failed to load page"
                                }
                            }
                        }

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // Prefer a normal Chrome mobile UA (sites often block "; wv")
                            userAgentString = userAgentString
                                .replace("; wv", "")
                                .replace("Version/4.0 ", "")
                            headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let {
                                userAgentString = it
                            }
                        }

                        webViewRef = this
                        if (loadUrl.isNotBlank()) {
                            loadedUrl = loadUrl
                            if (headerMap.isEmpty()) {
                                loadUrl(loadUrl)
                            } else {
                                loadUrl(loadUrl, headerMap)
                            }
                        }
                    }
                },
                update = { webView ->
                    // Only load when the target URL actually changes (avoid reload loops)
                    if (loadUrl.isNotBlank() && loadUrl != loadedUrl) {
                        loadedUrl = loadUrl
                        pageError = null
                        isLoading = true
                        if (headerMap.isEmpty()) {
                            webView.loadUrl(loadUrl)
                        } else {
                            webView.loadUrl(loadUrl, headerMap)
                        }
                    }
                }
            )

            if (isLoading && pageError == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            pageError?.let { err ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't open site", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Some sites block emulator networks or reset connections. Try again, use a different source, or open on a physical device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        pageError = null
                        isLoading = true
                        webViewRef?.reload()
                    }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
