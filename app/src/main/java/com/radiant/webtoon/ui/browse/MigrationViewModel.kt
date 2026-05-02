package com.radiant.webtoon.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.source.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MigrationViewModel(private val repository: WebtoonRepository) : ViewModel() {

    private val _mangasToMigrate = MutableStateFlow<List<Manga>>(emptyList())
    val mangasToMigrate: StateFlow<List<Manga>> = _mangasToMigrate.asStateFlow()

    private val _isMigrating = MutableStateFlow(false)
    val isMigrating: StateFlow<Boolean> = _isMigrating.asStateFlow()

    fun loadMangasBySource(sourceId: String) {
        viewModelScope.launch {
            repository.mangaDao.getMangaBySource(sourceId).collect {
                _mangasToMigrate.value = it
            }
        }
    }

    /**
     * Phase 2: Migrate reading progress and swap source/URL.
     */
    fun migrateWebtoon(oldManga: Manga, newWebtoonData: Webtoon, newSourceId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _isMigrating.value = true
            try {
                val newSource = repository.extensionManager.getSource(newSourceId) 
                    ?: throw Exception("Target source not found")
                
                // Step A: Fetch fresh chapter list from the NEW source
                val newChaptersRaw = newSource.getChapterList(newWebtoonData.id)
                
                // Step B: Progress Sync
                val oldChapters = repository.getChaptersForManga(oldManga.url)
                val readChapters = oldChapters.filter { it.read }
                
                val newChapters = newChaptersRaw.map { remote ->
                    // Attempt to find a matching chapter by number
                    val matchingOld = readChapters.find { 
                        it.chapter_number == remote.number?.toFloat() 
                    }
                    
                    com.radiant.webtoon.core.database.Chapter(
                        url = remote.id,
                        manga_id = newWebtoonData.id,
                        name = remote.title,
                        chapter_number = remote.number?.toFloat() ?: -1f,
                        source = newSourceId,
                        read = matchingOld != null,
                        bookmark = matchingOld?.bookmark ?: false,
                        last_page_read = matchingOld?.last_page_read ?: 0,
                        date_fetch = 0L // Set to 0 so migration doesn't flood Updates screen
                    )
                }
                
                // Step C: Execute Atomic Swap in DB
                repository.executeMigration(
                    oldUrl = oldManga.url,
                    oldSource = oldManga.source,
                    newUrl = newWebtoonData.id,
                    newSource = newSourceId,
                    newThumbnail = newWebtoonData.thumbnail,
                    newChapters = newChapters
                )
                
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("MigrationViewModel", "Migration failed", e)
            } finally {
                _isMigrating.value = false
            }
        }
    }
    
    fun getMangaDao() = repository.mangaDao
}
