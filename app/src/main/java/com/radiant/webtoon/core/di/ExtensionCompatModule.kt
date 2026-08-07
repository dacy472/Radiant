package com.radiant.webtoon.core.di

import android.app.Application
import android.content.Context
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.database.HistoryDao
import com.radiant.webtoon.core.database.MangaDao
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.*

/**
 * Registers all singletons Tachiyomi/Mihon extensions resolve via Injekt.get().
 * Must run before any extension DexClassLoader load.
 */
@OptIn(ExperimentalSerializationApi::class)
object ExtensionCompatModule {

    fun install(application: Application) {
        Injekt.addSingleton<Application>(application)
        Injekt.addSingleton<Context>(application)
        Injekt.addSingleton(NetworkHelper(application))
        Injekt.addSingleton<PreferenceStore>(AndroidPreferenceStore(application))

        val database = (application as com.radiant.webtoon.WebtoonApplication).database
        Injekt.addSingleton(database.mangaDao())
        Injekt.addSingleton(database.chapterDao())
        Injekt.addSingleton(database.historyDao())

        Injekt.addSingleton(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            },
        )
        Injekt.addSingleton<ProtoBuf>(ProtoBuf)
    }

    fun mangaDao(): MangaDao = Injekt.get()
    fun chapterDao(): ChapterDao = Injekt.get()
    fun historyDao(): HistoryDao = Injekt.get()
}
