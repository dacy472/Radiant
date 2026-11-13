package com.radiant.webtoon.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MigrationViewModel(private val repository: WebtoonRepository) : ViewModel() {

    private val _mangasToMigrate = MutableStateFlow<List<Manga>>(emptyList())
    val mangasToMigrate: StateFlow<List<Manga>> = _mangasToMigrate.asStateFlow()

    private val _isMigrating = MutableStateFlow(false)
    val isMigrating: StateFlow<Boolean> = _isMigrating.asStateFlow()

    /** One-shot failure messages (shown as a snackbar; the user stays on the search screen). */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var loadJob: Job? = null

    fun loadMangasBySource(sourceId: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.mangaDao.getMangaBySource(sourceId).collect {
                _mangasToMigrate.value = it
            }
        }
    }

    /**
     * Loads the replacement's chapters from its source, then runs the single-transaction migration.
     * Ignored while another migration is in flight (double-tap guard).
     */
    fun migrateWebtoon(
        oldManga: Manga,
        newWebtoonData: Webtoon,
        newSourceId: String,
        onComplete: () -> Unit,
    ) {
        if (!_isMigrating.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val newWebtoonId = newWebtoonData.url?.takeIf { it.isNotBlank() } ?: newWebtoonData.id
                // Network happens here, outside the DB transaction.
                repository.loadWebtoonForMigration(newSourceId, newWebtoonId)
                repository.executeMigration(
                    oldUrl = oldManga.url,
                    oldSource = oldManga.source,
                    newUrl = newWebtoonId,
                    newSource = newSourceId,
                )
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MigrationViewModel", "Migration failed", e)
                _errors.tryEmit(
                    "Migration failed: " +
                        (e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
                )
            } finally {
                _isMigrating.value = false
            }
        }
    }
}
