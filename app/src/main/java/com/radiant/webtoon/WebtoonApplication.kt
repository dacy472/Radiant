package com.radiant.webtoon

import android.app.Application
import android.content.Context
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.settings.SettingsManager
import com.radiant.webtoon.core.source.ExtensionLoader
import com.radiant.webtoon.core.source.ExtensionManager
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
import kotlinx.serialization.json.Json
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.GET

class WebtoonApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): ImageLoader {
        // Use our custom OkHttpClient so Coil inherits Cloudflare cookies, headers, and interceptors
        val networkHelper: NetworkHelper by injectLazy()
        
        val imageClient = networkHelper.client.newBuilder()
            // We still want a dedicated client for images, but without the dispatcher limits
            .addInterceptor(eu.kanade.tachiyomi.network.SequentialInterceptor()) 
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.40) // Allow Coil to use 40% of app RAM for massive webtoons
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
    }

    private fun dummyShrinkerBypass() {
        // Reduced to minimal references to keep the methods alive
        val headers = okhttp3.Headers.Builder().build()
        val cache = okhttp3.CacheControl.Builder().build()
        val body = okhttp3.RequestBody.create(null, "")

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
