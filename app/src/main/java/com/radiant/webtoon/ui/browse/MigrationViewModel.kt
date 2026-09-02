package com.radiant.webtoon.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.usecase.MangaIdentifier
import com.radiant.webtoon.core.usecase.MigrateWebtoonUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MigrationViewModel(private val repository: WebtoonRepository) : ViewModel() {

    private val migrateWebtoonUseCase = MigrateWebtoonUseCase(repository)

    private val _mangasToMigrate = MutableStateFlow<List<Manga>>(emptyList())
    val mangasToMigrate: StateFlow<List<Manga>> = _mangasToMigrate.asStateFlow()

    private val _isMigrating = MutableStateFlow(false)
    val isMigrating: StateFlow<Boolean> = _isMigrating.asStateFlow()

    private var loadJob: Job? = null

    fun loadMangasBySource(sourceId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.mangaDao.getMangaBySource(sourceId).collect {
                _mangasToMigrate.value = it
            }
        }
    }

    fun migrateWebtoon(
        oldManga: Manga,
        newWebtoonData: Webtoon,
        newSourceId: String,
        onComplete: () -> Unit,
    ) {
        viewModelScope.launch {
            _isMigrating.value = true
            try {
                val newWebtoonId = newWebtoonData.url?.takeIf { it.isNotBlank() } ?: newWebtoonData.id
                migrateWebtoonUseCase(
                    oldId = MangaIdentifier(url = oldManga.url, sourceId = oldManga.source),
                    newId = MangaIdentifier(url = newWebtoonId, sourceId = newSourceId),
                    newWebtoon = newWebtoonData,
                )
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("MigrationViewModel", "Migration failed", e)
            } finally {
                _isMigrating.value = false
            }
        }
    }
}
