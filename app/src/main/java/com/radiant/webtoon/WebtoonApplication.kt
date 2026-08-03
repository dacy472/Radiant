package com.radiant.webtoon

import android.app.Application
import android.content.Context
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.settings.SettingsManager
import com.radiant.webtoon.core.source.ExtensionLoader
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.service.LibraryUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import uy.kohesive.injekt.*
import uy.kohesive.injekt.api.*
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.GET

class WebtoonApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        // Use our custom OkHttpClient so Coil inherits Cloudflare cookies, headers, and interceptors
        val networkHelper: NetworkHelper by injectLazy()
        
        val imageClient = networkHelper.client.newBuilder()
            // Bound image concurrency to a small pool instead of a hard 1-at-a-time mutex
            // (old SequentialInterceptor) or fully unbounded parallel requests.
            .addInterceptor(
                eu.kanade.tachiyomi.network.BoundedConcurrencyInterceptor(
                    com.radiant.webtoon.core.concurrent.AppWorkQueues.imageFetch
                )
            )
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .components {
                // Many source sites (e.g. Mangabat, Asura Scans) hotlink-protect their cover
                // images and require the site's own Referer/User-Agent headers, which the
                // extension exposes via Source.getWebViewHeaders(). Without this, covers on
                // browse/search/library grids can silently fail to load.
                add(SourceHeaderInterceptor(extensionManager))
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // Keep webtoon pages without eating half the heap
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB disk cache
                    .build()
            }
            .crossfade(true)
            .build()
    }

    val database by lazy { WebtoonDatabase.getDatabase(this) }
    val settingsManager by lazy { SettingsManager(this) }
    val extensionLoader by lazy { ExtensionLoader(this) }
    val extensionManager by lazy {
        ExtensionManager(
            this,
            extensionLoader,
            database.extensionRepoDao(),
            database.installedExtensionDao(),
            settingsManager,
            applicationScope
        )
    }
    val repository by lazy {
        WebtoonRepository(
            this,
            extensionManager,
            database.mangaDao(),
            database.chapterDao(),
            database.historyDao(),
            database.downloadDao(),
            database
        )
    }

    val downloadQueueManager by lazy {
        com.radiant.webtoon.core.service.DownloadQueueManager(
            this,
            repository,
            settingsManager
        )
    }

    val backupService by lazy {
        com.radiant.webtoon.core.backup.BackupService(this, database)
    }

    val trackerService by lazy {
        com.radiant.webtoon.core.tracker.TrackerService(settingsManager)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun onCreate() {
        super.onCreate()
        
        // Initialize service locator for Tachiyomi extensions
        Injekt.addSingleton<Application>(this)
        Injekt.addSingleton<Context>(this)
        Injekt.addSingleton(NetworkHelper(this))
        Injekt.addSingleton<PreferenceStore>(AndroidPreferenceStore(this))
        Injekt.addSingleton(database.mangaDao())
        Injekt.addSingleton(database.chapterDao())
        Injekt.addSingleton(database.historyDao())
        Injekt.addSingleton(Json { ignoreUnknownKeys = true; isLenient = true })
        // Keiyoushi utils resolve ProtoBuf via Injekt.get() (e.g. Asura Scans parseAsProto)
        Injekt.addSingleton<ProtoBuf>(ProtoBuf)

        // Library updates are manual-only (Updates tab). Cancel any periodic work left over
        // from older builds that scheduled daily/6h/12h background checks.
        LibraryUpdateWorker.cancel(this@WebtoonApplication)
        applicationScope.launch {
            settingsManager.migrateAutoUpdateToManual()
        }
    }

    private fun dummyShrinkerBypass() {
        // Reduced to minimal references to keep the methods alive
        val headers = okhttp3.Headers.Builder().build()
        val cache = okhttp3.CacheControl.Builder().build()
        val body = "".toRequestBody(null)

        // GET variants
        eu.kanade.tachiyomi.network.GET("https://dummy.com")
        eu.kanade.tachiyomi.network.GET("https://dummy.com", headers)
        eu.kanade.tachiyomi.network.GET("https://dummy.com", headers, cache)

        val dummyUrl = okhttp3.HttpUrl.Builder().scheme("https").host("dummy.com").build()

        eu.kanade.tachiyomi.network.GET(dummyUrl)
        eu.kanade.tachiyomi.network.GET(dummyUrl, headers)
        eu.kanade.tachiyomi.network.GET(dummyUrl, headers, cache)

        // POST variants
        eu.kanade.tachiyomi.network.POST("https://dummy.com")
        eu.kanade.tachiyomi.network.POST("https://dummy.com", headers)
        eu.kanade.tachiyomi.network.POST("https://dummy.com", headers, body)
        eu.kanade.tachiyomi.network.POST("https://dummy.com", headers, body, cache)
    }
}

/**
 * Attaches the matching installed source's headers (e.g. Referer, User-Agent) to image
 * requests, so hotlink-protected covers/pages load the same way they would from within the
 * source itself. Matches purely by comparing the image URL's host against each installed
 * source's [com.radiant.webtoon.core.source.Source.baseUrl] host.
 */
private class SourceHeaderInterceptor(
    private val extensionManager: ExtensionManager
) : coil.intercept.Interceptor {

    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val request = chain.request
        val url = (request.data as? String) ?: (request.data as? android.net.Uri)?.toString()
        // Skip headers already explicitly set by the caller (e.g. the reader screen sets its
        // own per-page headers) to avoid sending duplicate header values.
        val headers = url?.let { resolveHeadersForUrl(it) }
            .orEmpty()
            .filterKeys { key -> request.headers.values(key).isEmpty() }

        val finalRequest = if (headers.isEmpty()) {
            request
        } else {
            request.newBuilder().apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }.build()
        }
        return chain.proceed(finalRequest)
    }

    private fun resolveHeadersForUrl(url: String): Map<String, String> {
        return try {
            val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
            extensionManager.installedExtensions.value
                .firstOrNull { source ->
                    val sourceHost = normalizedBaseUrl(source.baseUrl)?.toHttpUrlOrNull()?.host ?: return@firstOrNull false
                    host.equals(sourceHost, ignoreCase = true) ||
                        host.endsWith(".$sourceHost", ignoreCase = true) ||
                        sourceHost.endsWith(".$host", ignoreCase = true)
                }
                ?.getWebViewHeaders()
                ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun normalizedBaseUrl(baseUrl: String): String? {
        if (baseUrl.isBlank()) return null
        return if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) baseUrl else "https://$baseUrl"
    }
}
