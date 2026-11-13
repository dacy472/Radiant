package com.radiant.webtoon.ui.browse

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.source.Source
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Pairs a source with how many library entries it has — shown in the Migrate tab. */
data class MigrateItem(
    val sourceId: String,
    /** Null when the extension that provided this source is no longer installed. */
    val source: Source?,
    val libraryCount: Int
)

class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WebtoonApplication
    private val mangaDao = app.database.mangaDao()
    private val extensionManager = app.extensionManager

    /**
     * Reactive list for the Migrate tab.
     * Combines library source counts with the live installed extensions list,
     * keeping only sources that actually have library entries.
     */
    val migrateItems: StateFlow<List<MigrateItem>> = combine(
        mangaDao.getLibrarySourceCounts(),
        extensionManager.installedExtensions
    ) { counts, sources ->
        val sourceMap: Map<String, Source> = sources.associateBy { it.id }
        counts
            .filter { it.count > 0 }
            .map { count ->
                MigrateItem(
                    sourceId = count.sourceId,
                    source = sourceMap[count.sourceId],
                    libraryCount = count.count,
                )
            }
            // Installed sources first, then unavailable ones; busiest first within each group.
            .sortedWith(compareBy<MigrateItem> { it.source == null }.thenByDescending { it.libraryCount })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
}
