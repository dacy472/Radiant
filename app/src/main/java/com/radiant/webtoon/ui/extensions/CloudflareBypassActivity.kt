package com.radiant.webtoon.ui.extensions

import android.annotation.SuppressLint
import android.os.Bundle
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CloudflareBypassActivity : ComponentActivity() {

    private val networkHelper: NetworkHelper = Injekt.get()
    private var webView: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: "https://google.com"
        val extensionName = intent.getStringExtra("name") ?: "Extension"

        setContent {
            var currentUrl by remember { mutableStateOf(url) }
            var canGoBack by remember { mutableStateOf(false) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(extensionName, style = MaterialTheme.typography.titleMedium)
                                Text(currentUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { webView?.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            Button(
                                onClick = { 
                                    syncAndFinish()
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("DONE")
                            }
                        }
                    )
                }
            ) { padding ->
                AndroidViewBinding(padding, url) { wv ->
                    webView = wv
                }
            }
        }
    }

    @Composable
    private fun AndroidViewBinding(padding: PaddingValues, url: String, onWebViewCreated: (WebView) -> Unit) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // Set a generic mobile user agent if none is set
                        userAgentString = networkHelper.userAgentInterceptor.userAgent
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // We could auto-sync here if we detect specific cookies
                        }
                    }
                    
                    webChromeClient = WebChromeClient()
                    
                    loadUrl(url)
                    onWebViewCreated(this)
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize()
        )
    }

    private fun syncAndFinish() {
        webView?.let { view ->
            val newUserAgent = view.settings.userAgentString
            networkHelper.userAgentInterceptor.userAgent = newUserAgent
            
            // Cookies are automatically synced via WebViewCookieJar as it uses CookieManager.getInstance()
            // which handles the shared preference storage for WebViews.
            
            android.util.Log.d("CloudflareBypass", "Synced User-Agent: $newUserAgent")
            finish()
        }
    }
}
