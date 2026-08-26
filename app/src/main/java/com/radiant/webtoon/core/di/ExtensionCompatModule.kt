package com.radiant.webtoon.core.di

import android.app.Application
import android.content.Context
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.database.HistoryDao
import com.radiant.webtoon.core.database.MangaDao
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Injekt DI bridge for Tachiyomi/Mihon extensions loaded via DexClassLoader.
 *
 * Extensions resolve host services with `Injekt.get()` / `injectLazy()` against shared
 * host classes. This module must run before any extension DEX load.
 *
 * Registered singletons (extension-facing):
 * - [Application] / [Context]
 * - [NetworkHelper] — shared OkHttp client (no IgnoreGzip/Brotli interceptors)
 * - [JavaScriptEngine] — QuickJS sandbox for page-list decryption
 * - [PreferenceStore] — ConfigurableSource prefs
 * - Room DAOs, [Json], [ProtoBuf]
 */
@OptIn(ExperimentalSerializationApi::class)
object ExtensionCompatModule {

    @Volatile
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            Injekt.addSingleton<Application>(application)
            Injekt.addSingleton<Context>(application)

            // Single shared NetworkHelper — extensions call Injekt.get<NetworkHelper>().client
            Injekt.addSingletonFactory<NetworkHelper> { NetworkHelper(application) }

            // QuickJS-backed engine — extensions call Injekt.get<JavaScriptEngine>()
            Injekt.addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(application) }

            Injekt.addSingletonFactory<PreferenceStore> { AndroidPreferenceStore(application) }

            val database = (application as com.radiant.webtoon.WebtoonApplication).database
            Injekt.addSingletonFactory<MangaDao> { database.mangaDao() }
            Injekt.addSingletonFactory<ChapterDao> { database.chapterDao() }
            Injekt.addSingletonFactory<HistoryDao> { database.historyDao() }

            Injekt.addSingletonFactory {
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                }
            }
            Injekt.addSingletonFactory<ProtoBuf> { ProtoBuf }

            // Eagerly resolve so the first extension request never races construction
            Injekt.get<NetworkHelper>()
            Injekt.get<JavaScriptEngine>()

            installed = true
        }
    }

    fun mangaDao(): MangaDao = Injekt.get()
    fun chapterDao(): ChapterDao = Injekt.get()
    fun historyDao(): HistoryDao = Injekt.get()
}
