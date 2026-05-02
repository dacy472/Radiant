package com.radiant.webtoon.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.MangaDao
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.model.Chapter
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WebtoonDetailViewModel(
    private val repository: WebtoonRepository,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val downloadDao: com.radiant.webtoon.core.database.DownloadDao? = null,
    // Legacy param still accepted so the Factory compiles — will be removed when AppDatabase is retired
    private val webtoonDao: com.radiant.webtoon.core.database.WebtoonDao? = null
) : ViewModel() {

    private var currentSourceId: String = ""
    private var currentWebtoonId: String = ""

    // ── Database-first state ──

    /**
     * Reactive webtoon state driven by Room.
     * Emits instantly from the local DB; refreshFromNetwork() keeps it fresh.
     */
    private val _webtoon = MutableStateFlow<Webtoon?>(null)
    val webtoon: StateFlow<Webtoon?> = _webtoon

    /**
     * Reactive chapters list driven by Room.
     * Starts empty; updated when the network refresh upserts rows.
     */
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters

    // Favorite is derived from the Room manga row
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite

    // Loading states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _downloadTasks = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadStatus>> = _downloadTasks

    /**
     * Entry point called by the Screen with the navigation arguments.
     * Step 1: Reads instantly from Room (0ms latency).
     * Step 2: Kicks off a background network refresh.
     */
    fun loadWebtoon(sourceId: String, webtoonId: String) {
        currentSourceId = sourceId
        currentWebtoonId = webtoonId
        _isLoading.value = true

        viewModelScope.launch {
            // ── Step 1: Serve cached data from Room immediately ──
            val cached = mangaDao.getManga(webtoonId, sourceId)
            if (cached != null) {
                _webtoon.value = cached.toWebtoon()
                _isFavorite.value = cached.favorite
                android.util.Log.d("DetailVM", "Served ${cached.title} from DB instantly")
            }

            val cachedChapters = chapterDao.getChapters(webtoonId)
            if (cachedChapters.isNotEmpty()) {
                _chapters.value = cachedChapters.map { it.toModelChapter() }
                android.util.Log.d("DetailVM", "Served ${cachedChapters.size} chapters from DB instantly")
            }

            _isLoading.value = (cached == null) // Only show spinner if nothing cached
            updateDownloadStatuses()

            // ── Step 2: Background network refresh ──
            refreshFromNetwork()
        }

        // ── Observe Room continuously so UI updates when DB changes ──
        viewModelScope.launch {
            mangaDao.getMangaFlow(webtoonId, sourceId).collect { manga ->
                if (manga != null) {
                    _webtoon.value = manga.toWebtoon()
                    _isFavorite.value = manga.favorite
                }
            }
        }

        viewModelScope.launch {
            chapterDao.getChaptersFlow(webtoonId).collect { chapters ->
                _chapters.value = chapters.map { it.toModelChapter() }
            }
        }

        // ── Reactively observe download statuses so icons update live ──
        viewModelScope.launch {
            downloadDao?.getAllFlow()?.collect { tasks ->
                _downloadTasks.value = tasks.associate { it.chapterId to it.status }
            } ?: updateDownloadStatuses()
        }
    }

    /**
     * Fetches fresh data from the network and upserts into Room.
     * The DB Flows above will automatically push the update to the UI.
     */
    fun refreshFromNetwork() {
        val sourceId = currentSourceId.ifEmpty { return }
        val webtoonId = currentWebtoonId.ifEmpty { return }
        _isRefreshing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (detail, modelChapters) = repository.getWebtoonDetailAndChapters(sourceId, webtoonId)
                android.util.Log.d("DetailVM", "Network fetch complete: ${detail.title}, ${modelChapters.size} chapters")

                // Upsert webtoon into Room — preserve existing favorite status
                val existing = mangaDao.getManga(webtoonId, sourceId)
                val isFav = existing?.favorite ?: false
                mangaDao.insert(
                    Manga(
                        url = detail.id,
                        source = sourceId,
                        title = detail.title,
                        author = detail.author,
                        description = detail.description,
                        status = detail.status,
                        thumbnail_url = detail.thumbnail,
                        genre = detail.genres.joinToString(", "),
                        favorite = isFav,
                        initialized = true,
                        last_update = System.currentTimeMillis()
                    )
                )

                // Upsert chapters into Room
                val dbChapters = modelChapters.map { ch ->
                    com.radiant.webtoon.core.database.Chapter(
                        url = ch.id,
                        manga_id = webtoonId,
                        name = ch.title,
                        chapter_number = ch.number?.toFloat() ?: -1f,
                        source = sourceId,
                        date_fetch = 0L // Set to 0 so initial fetch doesn't flood Updates screen
                    )
                }
                chapterDao.insertAll(dbChapters)
                android.util.Log.d("DetailVM", "Upserted ${dbChapters.size} chapters into Room")

            } catch (e: Exception) {
                android.util.Log.e("DetailVM", "Network refresh failed: ${e.message}")
                // Silently keep serving cached data
            } finally {
                _isRefreshing.value = false
                _isLoading.value = false
            }
        }
    }

    /** Pull-to-refresh handler from the UI. */
    fun refreshWebtoon() = refreshFromNetwork()

    private suspend fun updateDownloadStatuses() {
        val allDownloads = repository.getDownloads()
        _downloadTasks.value = allDownloads.associate { it.chapterId to it.status }
    }

    fun downloadChapter(context: android.content.Context, chapterId: String, chapterTitle: String) {
        val webtoon = _webtoon.value ?: return
        val sourceId = currentSourceId

        val app = context.applicationContext as com.radiant.webtoon.WebtoonApplication
        app.downloadQueueManager.addDownload(
            chapterId = chapterId,
            sourceId = sourceId,
            webtoonId = webtoon.id,
            webtoonTitle = webtoon.title,
            chapterTitle = chapterTitle
        )
        viewModelScope.launch {
            updateDownloadStatuses()
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val sourceId = currentSourceId
            val webtoonId = currentWebtoonId
            val manga = mangaDao.getManga(webtoonId, sourceId) ?: return@launch

            val newFav = !manga.favorite
            mangaDao.updateFavorite(
                url = webtoonId,
                source = sourceId,
                isFavorite = newFav,
                date = if (newFav) System.currentTimeMillis() else 0
            )
            // The getMangaFlow collector above will automatically push the updated value to _isFavorite
        }
    }

    // ── Mapper extensions ──

    private fun Manga.toWebtoon(): Webtoon = Webtoon(
        id = url,
        title = title,
        thumbnail = thumbnail_url ?: "",
        url = url,
        status = status,
        author = author,
        description = description,
        genres = genre?.split(", ")?.map { it.trim() } ?: emptyList()
    )

    private fun com.radiant.webtoon.core.database.Chapter.toModelChapter(): Chapter = Chapter(
        id = url,
        title = name,
        url = url,
        number = chapter_number.toDouble().takeIf { it >= 0 }
    )
}
