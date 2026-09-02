package com.radiant.webtoon.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.MangaDao
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.local.LocalSourceIds
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebtoonDetailViewModel(
    private val repository: WebtoonRepository,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val downloadDao: com.radiant.webtoon.core.database.DownloadDao? = null,
    private val downloadedChapterDao: com.radiant.webtoon.core.database.DownloadedChapterDao? = null,
) : ViewModel() {

    private var currentSourceId: String = ""
    private var currentWebtoonId: String = ""
    private var repairAttempted = false
    private val refreshMutex = Mutex()
    private var activeRefresh: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Tracks which webtoonId already received a first-open auto-refresh this session. */
    private var lastAutoRefreshedId: String? = null

    // ── Database-first state ──

    /**
     * Reactive webtoon state driven by Room.
     * Emits instantly from the local DB; refreshFromNetwork() keeps it fresh.
     */
    private val _webtoon = MutableStateFlow<Webtoon?>(null)
    val webtoon: StateFlow<Webtoon?> = _webtoon

    /**
     * Reactive chapters list driven by Room [ChapterDao.getChaptersFlow].
     * Starts empty; [Chapter.read] updates (e.g. [ChapterDao.updateRead]) emit here.
     */
    private val _chapters = MutableStateFlow<List<com.radiant.webtoon.core.database.Chapter>>(emptyList())
    val chapters: StateFlow<List<com.radiant.webtoon.core.database.Chapter>> = _chapters

    /** Latest Room manga row (null = not yet in DB). Used for first-open auto-fetch. */
    private val _mangaRow = MutableStateFlow<Manga?>(null)

    // Favorite is derived from the Room manga row
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite

    // Loading states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _refreshToastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshToastMessage: SharedFlow<String> = _refreshToastMessage.asSharedFlow()

    private val _downloadTasks = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadStatus>> = _downloadTasks

    /**
     * Multi-select identity is [Chapter.url] (composite PK with manga_id — no Long id).
     */
    private val _selectedChapterIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedChapterIds: StateFlow<Set<String>> = _selectedChapterIds.asStateFlow()

    val isInSelectionMode: StateFlow<Boolean> = _selectedChapterIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var appContext: android.content.Context? = null

    /** Optional context for download queue; set from the Screen when available. */
    fun bindContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /**
     * Entry point called by the Screen with the navigation arguments.
     * Observes Room; auto-fetches from network once when chapters are empty / manga missing.
     */
    fun loadWebtoon(sourceId: String, webtoonId: String) {
        currentSourceId = sourceId
        currentWebtoonId = webtoonId
        repairAttempted = false
        lastAutoRefreshedId = null
        _error.value = null
        _isLoading.value = false
        clearSelection()

        // ── Observe Room continuously so UI is populated from local DB ──
        viewModelScope.launch {
            mangaDao.getMangaFlow(webtoonId, sourceId).collect { manga ->
                _mangaRow.value = manga
                if (manga != null) {
                    _webtoon.value = manga.toWebtoon()
                    _isFavorite.value = manga.favorite
                } else {
                    _webtoon.value = null
                    _isFavorite.value = false
                }
                maybeAutoRefreshFromRoom(webtoonId)
            }
        }

        viewModelScope.launch {
            chapterDao.getChaptersFlow(webtoonId).collect { chapters ->
                _chapters.value = chapters
                // Drop selections for chapters no longer present
                val urls = chapters.map { it.url }.toSet()
                _selectedChapterIds.value = _selectedChapterIds.value.filter { it in urls }.toSet()
                maybeAutoRefreshFromRoom(webtoonId)
            }
        }

        // ── Reactively observe download statuses so icons update live ──
        viewModelScope.launch {
            val dao = downloadDao
            val indexDao = downloadedChapterDao
            if (dao != null && indexDao != null) {
                combine(
                    dao.getAllFlow(),
                    indexDao.getByWebtoonFlow(sourceId, webtoonId),
                ) { tasks, downloaded ->
                    val map = tasks
                        .filter { it.webtoonId == webtoonId && it.sourceId == sourceId }
                        .filter { it.status != DownloadStatus.COMPLETED }
                        .associate { it.chapterId to it.status }
                        .toMutableMap()
                    downloaded.forEach { map[it.chapterId] = DownloadStatus.COMPLETED }
                    map
                }.collect { _downloadTasks.value = it }
            } else {
                updateDownloadStatuses()
            }
        }
    }

    /**
     * First-open only: if Room has no chapters or manga is missing/uninitialized,
     * trigger one network refresh. Never repeats for the same [loadWebtoon] session.
     */
    private fun maybeAutoRefreshFromRoom(webtoonId: String) {
        if (webtoonId != currentWebtoonId) return
        if (lastAutoRefreshedId == webtoonId) return
        if (_isRefreshing.value) return

        val chaptersEmpty = _chapters.value.isEmpty()
        val manga = _mangaRow.value
        val uninitialized = manga == null || !manga.initialized
        if (!chaptersEmpty && !uninitialized) return

        // Only auto when chapters are empty (first open / missing list).
        // Uninitialized with existing chapters is unusual; still allow if chapters empty.
        if (!chaptersEmpty) return

        lastAutoRefreshedId = webtoonId
        refreshFromNetwork()
    }

    /**
     * Fetches fresh data from the network and upserts into Room.
     * The DB Flows above will automatically push the update to the UI.
     */
    fun refreshFromNetwork() {
        val sourceId = currentSourceId.ifEmpty { return }
        val webtoonId = currentWebtoonId.ifEmpty { return }

        viewModelScope.launch(Dispatchers.IO) {
            val existing = activeRefresh
            if (existing != null && existing.isActive) {
                existing.await()
                return@launch
            }

            refreshMutex.withLock {
                val inFlight = activeRefresh
                if (inFlight != null && inFlight.isActive) {
                    inFlight.await()
                    return@withLock
                }

                val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                activeRefresh = deferred
                _isRefreshing.value = true
                try {
                    performNetworkRefresh(sourceId, webtoonId)
                    deferred.complete(Unit)
                } catch (e: Throwable) {
                    deferred.completeExceptionally(e)
                } finally {
                    _isRefreshing.value = false
                    if (activeRefresh === deferred) {
                        activeRefresh = null
                    }
                }
            }
        }
    }

    private suspend fun performNetworkRefresh(sourceId: String, webtoonId: String) {
        try {
            if (sourceId == LocalSourceIds.LOCAL_SOURCE_ID) {
                repository.refreshLocalLibrary()
                return
            }
            val (detail, modelChapters) = repository.getWebtoonDetailAndChapters(sourceId, webtoonId)
            android.util.Log.d("DetailVM", "Network fetch complete: ${detail.title}, ${modelChapters.size} chapters")
            _error.value = null

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

            val existingChapters = chapterDao.getChapters(webtoonId).associateBy { it.url }
            val dbChapters = modelChapters.map { ch ->
                val prior = existingChapters[ch.id]
                com.radiant.webtoon.core.database.Chapter(
                    url = ch.id,
                    manga_id = webtoonId,
                    name = ch.title,
                    scanlator = prior?.scanlator,
                    read = prior?.read ?: false,
                    bookmark = prior?.bookmark ?: false,
                    last_page_read = prior?.last_page_read ?: 0,
                    date_fetch = prior?.date_fetch ?: 0L,
                    date_upload = com.radiant.webtoon.core.util.ChapterDates.millisFrom(
                        rawDate = ch.date,
                        millis = ch.dateUploadMillis,
                    ).takeIf { it > 0L } ?: prior?.date_upload ?: 0L,
                    chapter_number = ch.number?.toFloat() ?: -1f,
                    source = sourceId,
                )
            }
            chapterDao.insertAll(dbChapters)
            android.util.Log.d("DetailVM", "Upserted ${dbChapters.size} chapters into Room")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!repairAttempted && isSerializationFailure(e)) {
                repairAttempted = true
                val repaired = repository.extensionManager.repairSourceAfterSerializationFailure(sourceId)
                if (repaired) {
                    performNetworkRefresh(sourceId, webtoonId)
                    return
                }
            }
            handleRefreshFailure(e)
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun handleRefreshFailure(e: Throwable) {
        android.util.Log.e("DetailVM", "Network refresh failed: ${e.message}", e)
        val hasOfflineData = _webtoon.value != null || _chapters.value.isNotEmpty()
        if (hasOfflineData) {
            _error.value = null
            _refreshToastMessage.emit("Unable to refresh latest chapters")
            return
        }

        val message = when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> "Timed out loading title details"
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Failed to load title details"
        }
        _error.value = message
    }

    /** Pull-to-refresh handler from the UI. */
    fun refreshWebtoon() = refreshFromNetwork()

    private suspend fun updateDownloadStatuses() {
        val allDownloads = repository.getDownloads()
        val downloadedIds = if (currentSourceId.isNotEmpty() && currentWebtoonId.isNotEmpty()) {
            repository.getDownloadedChapterIds(currentSourceId, currentWebtoonId).toSet()
        } else {
            emptySet()
        }
        _downloadTasks.value = buildMap {
            allDownloads
                .filter { it.webtoonId == currentWebtoonId && it.sourceId == currentSourceId }
                .filter { it.status != DownloadStatus.COMPLETED }
                .forEach { put(it.chapterId, it.status) }
            downloadedIds.forEach { put(it, DownloadStatus.COMPLETED) }
        }
    }

    fun downloadChapter(context: android.content.Context, chapterId: String, chapterTitle: String) {
        if (currentSourceId == LocalSourceIds.LOCAL_SOURCE_ID) return
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

    /**
     * Queue every chapter that is not already completed, oldest → newest (chapter 1 → last).
     */
    fun downloadAllMissing(context: android.content.Context) {
        if (currentSourceId == LocalSourceIds.LOCAL_SOURCE_ID) return
        viewModelScope.launch {
            val webtoon = _webtoon.value ?: return@launch
            val statuses = _downloadTasks.value
            val chapters = chapterDao.getChapters(currentWebtoonId)
                .sortedBy { it.chapter_number }
                .filter { statuses[it.url] != DownloadStatus.COMPLETED }
            enqueueChapters(context, webtoon, chapters)
        }
    }

    fun downloadUnread(context: android.content.Context) {
        if (currentSourceId == LocalSourceIds.LOCAL_SOURCE_ID) return
        viewModelScope.launch {
            val webtoon = _webtoon.value ?: return@launch
            val chapters = chapterDao.getChapters(currentWebtoonId)
                .filter { !it.read }
                .sortedBy { it.chapter_number }
            enqueueChapters(context, webtoon, chapters)
        }
    }

    fun downloadNext(context: android.content.Context, count: Int = 5) {
        if (currentSourceId == LocalSourceIds.LOCAL_SOURCE_ID) return
        viewModelScope.launch {
            val webtoon = _webtoon.value ?: return@launch
            val chapters = chapterDao.getChapters(currentWebtoonId)
                .filter { !it.read }
                .sortedBy { it.chapter_number }
                .take(count)
            enqueueChapters(context, webtoon, chapters)
        }
    }

    /** Queues a caller-chosen subset of chapters (e.g. multi-select), oldest → newest. */
    fun downloadSelected(context: android.content.Context, chapterIds: Set<String>) {
        if (chapterIds.isEmpty() || currentSourceId == LocalSourceIds.LOCAL_SOURCE_ID) return
        viewModelScope.launch {
            val webtoon = _webtoon.value ?: return@launch
            val chapters = chapterDao.getChapters(currentWebtoonId)
                .filter { chapterIds.contains(it.url) }
                .sortedBy { it.chapter_number }
            enqueueChapters(context, webtoon, chapters)
        }
    }

    /** Batch-download currently selected chapters, then clear selection. */
    fun downloadSelected() {
        val ids = _selectedChapterIds.value
        if (ids.isEmpty()) return
        val ctx = appContext ?: return
        downloadSelected(ctx, ids)
        clearSelection()
    }

    /** Deletes a single completed chapter's downloaded files + DB row. */
    fun deleteDownloadedChapter(chapterId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedChapter(chapterId, currentSourceId)
            updateDownloadStatuses()
        }
    }

    /** Batch delete for multi-select "Delete downloads". */
    fun deleteDownloadedChapters(chapterIds: Set<String>) {
        if (chapterIds.isEmpty()) return
        viewModelScope.launch {
            repository.deleteDownloadedChapters(chapterIds.map { it to currentSourceId })
            updateDownloadStatuses()
        }
    }

    /** Delete downloads for selected chapters instantly, then clear selection. */
    fun deleteSelectedDownloads() {
        val ids = _selectedChapterIds.value
        if (ids.isEmpty()) return
        deleteDownloadedChapters(ids)
        clearSelection()
    }

    /** Marks a set of chapters read/unread — used by the multi-select toolbar. */
    fun markChaptersRead(chapterIds: Set<String>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        viewModelScope.launch {
            chapterDao.updateRead(currentWebtoonId, chapterIds.toList(), read)
        }
    }

    fun markSelectedAsRead(read: Boolean) {
        val ids = _selectedChapterIds.value
        if (ids.isEmpty()) return
        markChaptersRead(ids, read)
        clearSelection()
    }

    fun bookmarkSelected(bookmark: Boolean) {
        val ids = _selectedChapterIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chapterDao.updateBookmark(currentWebtoonId, ids.toList(), bookmark)
            clearSelection()
        }
    }

    // ── Multi-selection (identity = Chapter.url) ──

    fun toggleSelection(chapterId: String) {
        val current = _selectedChapterIds.value
        _selectedChapterIds.value = if (chapterId in current) current - chapterId else current + chapterId
    }

    fun selectAll() {
        _selectedChapterIds.value = _chapters.value.map { it.url }.toSet()
    }

    fun invertSelection() {
        val visible = _chapters.value.map { it.url }.toSet()
        val selected = _selectedChapterIds.value
        _selectedChapterIds.value = visible - selected
    }

    fun clearSelection() {
        _selectedChapterIds.value = emptySet()
    }

    private fun enqueueChapters(
        context: android.content.Context,
        webtoon: Webtoon,
        chapters: List<com.radiant.webtoon.core.database.Chapter>
    ) {
        if (chapters.isEmpty()) return
        val app = context.applicationContext as com.radiant.webtoon.WebtoonApplication
        app.downloadQueueManager.addDownloads(
            chapters.map { ch ->
                com.radiant.webtoon.core.download.DownloadManager.DownloadRequest(
                    chapterId = ch.url,
                    sourceId = currentSourceId,
                    webtoonId = webtoon.id,
                    webtoonTitle = webtoon.title,
                    chapterTitle = ch.name
                )
            }
        )
        viewModelScope.launch {
            updateDownloadStatuses()
        }
    }

    /** Absolute manga page URL for WebView (uses HttpSource.getMangaUrl when available). */
    fun getMangaPageUrl(): String {
        val relative = _webtoon.value?.url?.takeIf { it.isNotBlank() } ?: currentWebtoonId
        val source = repository.extensionManager.getSource(currentSourceId)
        return source?.getMangaPageUrl(relative) ?: relative
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

    private fun isSerializationFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is kotlinx.serialization.MissingFieldException) return true
            if (current.javaClass.name.contains("MissingFieldException")) return true
            current = current.cause
        }
        return false
    }
}
