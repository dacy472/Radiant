package com.radiant.webtoon

import android.app.Application
import android.content.Context
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.settings.SettingsManager
import com.radiant.webtoon.core.source.ExtensionLoader
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.service.ExtensionColdStartUpdate
import com.radiant.webtoon.core.service.ExtensionUpdateWorker
import com.radiant.webtoon.core.service.LibraryUpdateWorker
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.radiant.webtoon.core.image.NoStoreErrorResponseInterceptor
import com.radiant.webtoon.core.image.RadiantImageLoader
import com.radiant.webtoon.core.image.SourceImageCoilInterceptor
import com.radiant.webtoon.core.image.SourceImageOkHttpInterceptor
import com.radiant.webtoon.core.di.ExtensionCompatModule
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.GET

class WebtoonApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e("WebtoonApplication", "Background startup work failed", error)
        },
    )

    override fun newImageLoader(): ImageLoader {
        // Coil ImageLoaderFactory: custom OkHttp client + memory/disk caches (see RadiantImageLoader).
        val networkHelper: NetworkHelper by injectLazy()
        
        val imageClient = networkHelper.client.newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 16
                    maxRequestsPerHost = 3
                }
            )
            // Bound image concurrency to a small pool instead of a hard 1-at-a-time mutex
            // (old SequentialInterceptor) or fully unbounded parallel requests.
            .addInterceptor(
                eu.kanade.tachiyomi.network.BoundedConcurrencyInterceptor(
                    com.radiant.webtoon.core.concurrent.AppWorkQueues.imageFetch
                )
            )
            .addInterceptor(SourceImageOkHttpInterceptor(extensionManager))
            .addNetworkInterceptor(NoStoreErrorResponseInterceptor())
            // Absolute last network interceptor on the Coil clone (parent also logs HEADERS
            // before NoStore). SourceImageOkHttpInterceptor is an application interceptor,
            // so this sees the request after Referer injection.
            .addNetworkInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
            .build()

        return RadiantImageLoader.build(this, imageClient) {
            components {
                // Many source sites (e.g. Mangabat, Asura Scans) hotlink-protect their cover
                // images and require the site's own Referer/User-Agent headers, which the
                // extension exposes via Source.getWebViewHeaders(). Without this, covers on
                // browse/search/library grids can silently fail to load.
                add(SourceImageCoilInterceptor(extensionManager))
            }
        }
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
            database.downloadedChapterDao(),
            database
        )
    }

    val migrateWebtoonUseCase by lazy {
        com.radiant.webtoon.core.domain.usecase.MigrateWebtoonUseCase(database)
    }

    val downloadManager by lazy {
        com.radiant.webtoon.core.download.DownloadManager(
            this,
            repository,
            settingsManager,
            applicationScope,
        )
    }

    /** @deprecated Use [downloadManager] */
    val downloadQueueManager get() = downloadManager

    val backupService by lazy {
        com.radiant.webtoon.core.backup.BackupService(this, database)
    }

    val localLibraryCatalog by lazy { com.radiant.webtoon.core.local.LocalLibraryCatalog() }

    val localSource by lazy { com.radiant.webtoon.core.local.LocalSource(localLibraryCatalog) }

    val localLibraryManager by lazy {
        com.radiant.webtoon.core.local.LocalLibraryManager(
            context = this,
            settingsManager = settingsManager,
            database = database,
            catalog = localLibraryCatalog,
        )
    }

    val trackerService by lazy {
        com.radiant.webtoon.core.tracker.TrackerService(settingsManager)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun onCreate() {
        super.onCreate()

        com.radiant.webtoon.core.debug.PageListDebugLogger.init(this)
        // Must run before the first ImageLoader is created (lazy, on first composition).
        com.radiant.webtoon.core.image.ImageCacheMaintenance.runIfNeeded(this)

        // Full Injekt graph for Tachiyomi/Mihon extensions — must run before any DEX load
        ExtensionCompatModule.install(this)
        eu.kanade.tachiyomi.network.interceptor.ForegroundActivityHolder.register(this)

        LibraryUpdateWorker.cancel(this@WebtoonApplication)
        // Library chapter checks are manual-only (Updates tab). Never enqueue on startup.
        ExtensionUpdateWorker.schedule(this@WebtoonApplication)
        // Background: PackageManager → Room sync for system-installed extension APKs,
        // then purge / load / update check (see ExtensionColdStartUpdate).
        ExtensionColdStartUpdate.schedule(this@WebtoonApplication, applicationScope)
        applicationScope.launch {
            settingsManager.migrateStoredPreferenceTypes()
            settingsManager.migrateAutoUpdateToManual()
            settingsManager.unpinSource(com.radiant.webtoon.core.local.LocalSourceIds.LOCAL_SOURCE_ID)
            com.radiant.webtoon.core.download.DownloadIndexBackfill.runIfNeeded(
                context = this@WebtoonApplication,
                downloadedChapterDao = database.downloadedChapterDao(),
                downloadDao = database.downloadDao(),
                mangaDao = database.mangaDao(),
                chapterDao = database.chapterDao(),
                settingsManager = settingsManager,
            )
            localLibraryManager.refresh()
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
