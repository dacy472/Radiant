package com.radiant.webtoon.ui.reader

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.debug.PageListDebugLogger
import com.radiant.webtoon.core.model.ChapterPagesResult
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import com.radiant.webtoon.core.tracker.TrackerSyncAck
import com.radiant.webtoon.ui.reader.viewer.ReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Headers
import java.io.File
import java.util.zip.ZipFile

sealed class ReaderPageError {
    data class CloudflareBlocked(val chapterUrl: String) : ReaderPageError()
    data object NoPages : ReaderPageError()
}

class ReaderViewModel(
    application: Application,
    private val repository: WebtoonRepository,
) : AndroidViewModel(application) {
    private val settingsManager = getApplication<WebtoonApplication>().settingsManager

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _pageError = MutableStateFlow<ReaderPageError?>(null)
    val pageError: StateFlow<ReaderPageError?> = _pageError

    private val _pageListDebugEvent = MutableStateFlow<PageListDebugLogger.PageListDebugEvent?>(null)
    val pageListDebugEvent: StateFlow<PageListDebugLogger.PageListDebugEvent?> = _pageListDebugEvent

    private val _pageListDebugMode = MutableStateFlow(false)
    val pageListDebugMode: StateFlow<Boolean> = _pageListDebugMode

    /** HTTP headers from the source (User-Agent, Referer, etc.) for Coil image requests. */
    private val _sourceHeaders = MutableStateFlow(Headers.headersOf())
    val sourceHeaders: StateFlow<Headers> = _sourceHeaders

    // ── Reader Preferences ──────────────────────────────────────────────────
    /** Effective reading mode: per-series override if present, else the default preference. */
    private val _readingMode = MutableStateFlow(ReadingMode.DEFAULT)
    val readingMode: StateFlow<ReadingMode> = _readingMode

    /** Non-null when this series has its own reading mode (otherwise the default applies). */
    private val _seriesReadingModeOverride = MutableStateFlow<ReadingMode?>(null)
    val seriesReadingModeOverride: StateFlow<ReadingMode?> = _seriesReadingModeOverride

    private val _defaultReadingMode = MutableStateFlow(ReadingMode.DEFAULT)
    val defaultReadingMode: StateFlow<ReadingMode> = _defaultReadingMode

    private val _backgroundColor = MutableStateFlow(Color.Black)
    val backgroundColor: StateFlow<Color> = _backgroundColor

    /** Crop borders for the paged viewers (LTR / RTL / vertical pager). */
    private val _cropBordersPaged = MutableStateFlow(false)
    val cropBordersPaged: StateFlow<Boolean> = _cropBordersPaged

    /** Crop borders for the webtoon / continuous-vertical viewers. */
    private val _cropBordersWebtoon = MutableStateFlow(false)
    val cropBordersWebtoon: StateFlow<Boolean> = _cropBordersWebtoon

    /**
     * Crop borders for whichever viewer the current reading mode uses. The activity feeds this
     * into the decoder ([com.radiant.webtoon.core.image.crop.CropBordersDecoder] / the subsampling
     * region decoder), not into a UI scale or clip.
     */
    val cropBorders: StateFlow<Boolean> = combine(
        _readingMode,
        _cropBordersPaged,
        _cropBordersWebtoon,
    ) { mode, paged, webtoon ->
        if (mode.usesWebtoonCrop) webtoon else paged
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Zero-based page currently shown by the native viewer (drives the HUD scrubber and progress). */
    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex

    private val _menuVisible = MutableStateFlow(false)
    val menuVisible: StateFlow<Boolean> = _menuVisible

    private val _activeChapterTitle = MutableStateFlow("")
    val activeChapterTitle: StateFlow<String> = _activeChapterTitle

    var currentSeriesSourceId: String = ""
        private set
    var currentSeriesWebtoonId: String = ""
        private set
    private var markedReadChapterId: String? = null
    private val _seriesKey = MutableStateFlow<String?>(null)

    private val _nextChapter = MutableStateFlow<Chapter?>(null)
    val nextChapter: StateFlow<Chapter?> = _nextChapter

    private val _previousChapter = MutableStateFlow<Chapter?>(null)
    val previousChapter: StateFlow<Chapter?> = _previousChapter

    private val _hasNextChapter = MutableStateFlow(false)
    val hasNextChapter: StateFlow<Boolean> = _hasNextChapter

    private val _hasPreviousChapter = MutableStateFlow(false)
    val hasPreviousChapter: StateFlow<Boolean> = _hasPreviousChapter

    /** Bumped whenever a chapter starts loading so the UI resets scroll/pager to page 1. */
    private val _resetPositionTick = MutableStateFlow(0)
    val resetPositionTick: StateFlow<Int> = _resetPositionTick

    private val _activeChapterId = MutableStateFlow("")
    val activeChapterId: StateFlow<String> = _activeChapterId

    private val _loadGeneration = MutableStateFlow(0L)
    val loadGeneration: StateFlow<Long> = _loadGeneration

    private val _initialPageIndex = MutableStateFlow(0)
    val initialPageIndex: StateFlow<Int> = _initialPageIndex

    /** Saved position inside [initialPageIndex], 1/10000 of the page height (webtoon viewers). */
    private val _initialPageOffset = MutableStateFlow(0)
    val initialPageOffset: StateFlow<Int> = _initialPageOffset

    private var currentSourceId: String = ""
    private var currentWebtoonId: String = ""
    var currentChapterId: String = ""
        private set
    private var loadJob: Job? = null
    private var persistJob: Job? = null
    private var pendingPageIndex: Int? = null
    private var pendingPageOffset: Int = 0
    private var persistGeneration: Long = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seriesOverrideFlow: Flow<ReadingMode?> = _seriesKey.flatMapLatest { key ->
        if (key == null) flowOf(null)
        else settingsManager.readingModeOverride(key).map { id -> id?.let { ReadingMode.fromPrefId(it) } }
    }

    init {
        viewModelScope.launch {
            combine(
                settingsManager.readingDirection.map { ReadingMode.fromPrefId(it) },
                seriesOverrideFlow,
            ) { default, override ->
                _defaultReadingMode.value = default
                _seriesReadingModeOverride.value = override
                override ?: default
            }.collect { _readingMode.value = it }
        }
        viewModelScope.launch {
            settingsManager.pageListDebugMode.collect { enabled ->
                _pageListDebugMode.value = enabled
            }
        }
        viewModelScope.launch {
            settingsManager.cropBorders.collect { _cropBordersPaged.value = it }
        }
        viewModelScope.launch {
            settingsManager.cropBordersWebtoon.collect { _cropBordersWebtoon.value = it }
        }
    }

    /** Sets the reading mode for the current series only (falls back to the default mode otherwise). */
    fun setReadingMode(mode: ReadingMode) {
        val key = _seriesKey.value
        if (key == null) {
            setDefaultReadingMode(mode)
            return
        }
        viewModelScope.launch { settingsManager.setReadingModeOverride(key, mode.prefId) }
    }

    /** Sets the default reading mode used by every series without an override. */
    fun setDefaultReadingMode(mode: ReadingMode) {
        viewModelScope.launch { settingsManager.setReadingDirection(mode.prefId) }
    }

    /** Drops the per-series override so the default reading mode applies again. */
    fun clearSeriesReadingMode() {
        val key = _seriesKey.value ?: return
        viewModelScope.launch { settingsManager.setReadingModeOverride(key, null) }
    }

    fun setBackgroundColor(color: Color) { _backgroundColor.value = color }

    /** Toggles Crop Borders for whichever viewer family the current reading mode belongs to. */
    fun setCropBorders(crop: Boolean) {
        viewModelScope.launch {
            if (_readingMode.value.usesWebtoonCrop) settingsManager.setCropBordersWebtoon(crop)
            else settingsManager.setCropBorders(crop)
        }
    }

    fun setCropBordersPaged(crop: Boolean) {
        viewModelScope.launch { settingsManager.setCropBorders(crop) }
    }

    fun setCropBordersWebtoon(crop: Boolean) {
        viewModelScope.launch { settingsManager.setCropBordersWebtoon(crop) }
    }

    fun setMenuVisible(visible: Boolean) { _menuVisible.value = visible }

    /** `free`, `portrait`, `landscape` or `locked`; applied by [ReaderActivity]. */
    val readerOrientation: Flow<String> get() = settingsManager.readerOrientation

    fun setReaderOrientation(value: String) {
        viewModelScope.launch { settingsManager.setReaderOrientation(value) }
    }

    /**
     * Called by the native viewer whenever the visible page changes: persists the position and marks
     * the chapter read once the reader gets close to the end (same rules as the old Compose reader).
     */
    fun onPageSelected(pageIndex: Int, generation: Long, persist: Boolean = true) {
        if (generation != _loadGeneration.value) return
        _currentPageIndex.value = pageIndex.coerceAtLeast(0)
        val pageCount = _pages.value.size
        val chapterId = currentChapterId
        if (_isLoading.value || pageCount == 0 || chapterId.isBlank()) return
        if (persist) persistLastPage(pageIndex.coerceAtLeast(0), generation)
        if (markedReadChapterId != chapterId && ReaderSessionLogic.shouldMarkRead(
                isLoading = _isLoading.value,
                pagesReady = pageCount > 0,
                currentPageOneBased = pageIndex + 1,
                pageCount = pageCount,
                activeGeneration = generation,
                eventGeneration = generation,
            )
        ) {
            markedReadChapterId = chapterId
            markChapterReadWhenComplete(chapterId, currentWebtoonId, generation)
        }
    }

    fun markChapterReadWhenComplete(chapterId: String, webtoonId: String, generation: Long) {
        if (generation != _loadGeneration.value || chapterId != currentChapterId) return
        val sourceId = currentSourceId
        viewModelScope.launch {
            repository.markChapterRead(chapterId, webtoonId, sourceId, true)

            if (settingsManager.autoDeleteRead.first()) {
                if (repository.isChapterDownloaded(sourceId, chapterId)) {
                    repository.deleteDownloadedChapter(chapterId, sourceId)
                }
            }

            try {
                val app = getApplication<WebtoonApplication>()
                val track = app.database.trackDao()
                    .getForManga(webtoonId, sourceId)
                    .firstOrNull { it.trackerId == "anilist" }
                if (track != null) {
                    val chapters = repository.getChaptersForManga(webtoonId, sourceId)
                    val chapter = chapters.find { it.url == chapterId }
                    val number = chapter?.chapter_number?.toInt()?.coerceAtLeast(0) ?: 0
                    if (number > 0) {
                        val synced = app.trackerService.syncProgressChecked(track.remoteId, number)
                        val next = TrackerSyncAck.nextLastChapter(
                            localLastChapter = track.lastChapter,
                            syncedChapter = number,
                            syncSucceeded = synced.isSuccess,
                        )
                        if (next != track.lastChapter) {
                            app.database.trackDao().insert(track.copy(lastChapter = next))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Webtoon viewers report the top-most visible page plus how far into it the viewport starts,
     * so reopening lands on the same spot of a tall strip.
     */
    fun onReadPositionChanged(pageIndex: Int, offsetPermyriad: Int, generation: Long) {
        if (generation != _loadGeneration.value) return
        if (_isLoading.value || _pages.value.isEmpty()) return
        persistLastPage(pageIndex, generation, offsetPermyriad)
    }

    fun persistLastPage(pageIndex: Int, generation: Long, offsetPermyriad: Int = 0) {
        if (generation != _loadGeneration.value) return
        val chapterId = currentChapterId
        val mangaId = currentWebtoonId
        val sourceId = currentSourceId
        if (chapterId.isBlank() || mangaId.isBlank() || sourceId.isBlank()) return
        pendingPageIndex = pageIndex.coerceAtLeast(0)
        pendingPageOffset = offsetPermyriad.coerceIn(0, 9_999)
        persistGeneration = generation
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(400)
            writePendingPage()
        }
    }

    fun flushLastPage() {
        persistJob?.cancel()
        viewModelScope.launch { writePendingPage() }
    }

    private suspend fun writePendingPage() {
        val pageIndex = pendingPageIndex ?: return
        val generation = persistGeneration
        if (generation != _loadGeneration.value) return
        val chapterId = currentChapterId
        val mangaId = currentWebtoonId
        val sourceId = currentSourceId
        if (chapterId.isBlank() || mangaId.isBlank() || sourceId.isBlank()) return
        repository.updateReadPosition(chapterId, mangaId, sourceId, pageIndex, pendingPageOffset)
        pendingPageIndex = null
        pendingPageOffset = 0
    }
    // ────────────────────────────────────────────────────────────────────────

    fun loadNextChapter() {
        val chapter = _nextChapter.value ?: return
        if (currentSourceId.isBlank() || currentWebtoonId.isBlank()) return
        loadChapter(currentSourceId, currentWebtoonId, chapter.url, chapter.name)
    }

    fun loadPreviousChapter() {
        val chapter = _previousChapter.value ?: return
        if (currentSourceId.isBlank() || currentWebtoonId.isBlank()) return
        loadChapter(currentSourceId, currentWebtoonId, chapter.url, chapter.name)
    }

    /**
     * @param startPage zero-based page to open (e.g. from a deep link); `null` resumes the saved
     * `last_page_read` of the chapter.
     */
    fun loadChapter(
        sourceId: String,
        webtoonId: String,
        chapterId: String,
        chapterTitle: String,
        startPage: Int? = null,
    ) {
        loadJob?.cancel()
        val generation = _loadGeneration.value + 1
        _loadGeneration.value = generation
        currentSourceId = sourceId
        currentWebtoonId = webtoonId
        currentChapterId = chapterId
        currentSeriesSourceId = sourceId
        currentSeriesWebtoonId = webtoonId
        _seriesKey.value = ReadingMode.seriesKey(sourceId, webtoonId)
        _activeChapterId.value = chapterId
        _activeChapterTitle.value = chapterTitle
        _isLoading.value = true
        _pageError.value = null
        _pageListDebugEvent.value = null
        _pages.value = emptyList()
        _initialPageIndex.value = 0
        _initialPageOffset.value = 0
        _currentPageIndex.value = 0
        markedReadChapterId = null

        loadJob = viewModelScope.launch {
            try {
                refreshAdjacentChapters(webtoonId, chapterId)
                if (generation != _loadGeneration.value) return@launch

                val app = getApplication<WebtoonApplication>()
                val source = app.extensionManager.getSource(sourceId)
                _sourceHeaders.value = com.radiant.webtoon.core.image.SourceImageHeaders.toOkHttpHeaders(source)

                val savedChapter = repository.getChapter(chapterId, webtoonId, sourceId)
                val savedPage = savedChapter?.last_page_read ?: 0
                val savedOffset = savedChapter?.last_page_offset ?: 0
                val incognito = app.settingsManager.incognitoMode.first()
                if (!incognito) {
                    try {
                        val detail = repository.getWebtoonDetail(sourceId, webtoonId)
                        repository.logReadingHistory(
                            com.radiant.webtoon.core.database.History(
                                chapter_id = chapterId,
                                webtoon_id = webtoonId,
                                source_id = sourceId,
                                chapter_title = chapterTitle,
                                webtoon_title = detail.title,
                                webtoon_thumbnail = detail.thumbnail
                            )
                        )
                    } catch (_: Exception) {
                    }
                }

                val pages = run {
                    val downloaded = repository.getDownloadedChapter(sourceId, chapterId)
                    if (downloaded != null) {
                        val localPages = loadLocalPages(downloaded.localDirectory)
                        if (localPages.isNotEmpty()) return@run localPages
                    }
                    val result = repository.getChapterPagesResult(sourceId, chapterId, webtoonId)
                    if (result.pages.isEmpty()) {
                        _pageListDebugEvent.value = PageListDebugLogger.lastEventForChapter(chapterId)
                            ?: PageListDebugLogger.lastEvent()
                        _pageError.value = resolvePageError(result, chapterId, source)
                    }
                    result.pages
                }
                if (generation != _loadGeneration.value) return@launch
                _pages.value = pages
                val requested = startPage ?: savedPage
                val resumeAt = requested.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                _initialPageOffset.value = ReaderSessionLogic.resumePageOffset(
                    requestedPage = requested,
                    resumePage = resumeAt,
                    savedOffset = if (startPage == null) savedOffset else 0,
                )
                _initialPageIndex.value = resumeAt
                _currentPageIndex.value = resumeAt
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != _loadGeneration.value) return@launch
                _pages.value = emptyList()
                _pageListDebugEvent.value = PageListDebugLogger.lastEventForChapter(chapterId)
                    ?: PageListDebugLogger.lastEvent()
                val app = getApplication<WebtoonApplication>()
                val source = app.extensionManager.getSource(sourceId)
                if (e.message?.contains("cloudflare", ignoreCase = true) == true) {
                    _pageError.value = ReaderPageError.CloudflareBlocked(
                        chapterUrl = source?.getChapterPageUrl(chapterId) ?: chapterId,
                    )
                } else {
                    _pageError.value = ReaderPageError.NoPages
                }
            } finally {
                if (generation == _loadGeneration.value) {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Room stores chapters `ORDER BY chapter_number DESC` (highest number first).
     * Index - 1 is the next chapter; index + 1 is the previous.
     */
    private suspend fun refreshAdjacentChapters(webtoonId: String, chapterId: String) {
        try {
            val allChapters = repository.getChaptersForManga(webtoonId, currentSourceId)
            val currentIndex = allChapters.indexOfFirst { it.url == chapterId }
            if (currentIndex != -1) {
                _nextChapter.value = allChapters.getOrNull(currentIndex - 1)
                _previousChapter.value = allChapters.getOrNull(currentIndex + 1)
            } else {
                _nextChapter.value = null
                _previousChapter.value = null
            }
        } catch (_: Exception) {
            _nextChapter.value = null
            _previousChapter.value = null
        }
        _hasNextChapter.value = _nextChapter.value != null
        _hasPreviousChapter.value = _previousChapter.value != null
    }

    private fun resolvePageError(
        result: ChapterPagesResult,
        chapterId: String,
        source: com.radiant.webtoon.core.source.Source?,
    ): ReaderPageError {
        return if (result.isCloudflareBlocked()) {
            ReaderPageError.CloudflareBlocked(
                chapterUrl = result.chapterUrl ?: source?.getChapterPageUrl(chapterId) ?: chapterId,
            )
        } else {
            ReaderPageError.NoPages
        }
    }

    private fun loadLocalPages(localPath: String): List<String> {
        if (localPath.startsWith("content://")) {
            val docFile = DocumentFile.fromTreeUri(getApplication(), Uri.parse(localPath))
            if (docFile != null && docFile.isDirectory) {
                return docFile.listFiles()
                    .filter { name ->
                        val n = name.name?.lowercase() ?: return@filter false
                        n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                    }
                    .sortedBy { it.name?.substringBeforeLast(".")?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                    .map { it.uri.toString() }
            }
            return emptyList()
        }

        val file = File(localPath)
        if (!file.exists()) return emptyList()

        // CBZ / ZIP archive produced by ChapterDownloadWorker
        if (file.isFile && (localPath.endsWith(".cbz", ignoreCase = true) || localPath.endsWith(".zip", ignoreCase = true))) {
            return extractCbzToCache(file)
        }

        if (file.isDirectory) {
            return file.listFiles { _, name ->
                val n = name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
            }
                ?.sortedBy { it.name.substringBeforeLast(".").filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                ?.map { "file://${it.absolutePath}" }
                ?: emptyList()
        }

        return emptyList()
    }

    private fun extractCbzToCache(cbzFile: File): List<String> {
        val fingerprint = "${cbzFile.length()}-${cbzFile.lastModified()}"
        val cacheDir = File(
            getApplication<Application>().cacheDir,
            "cbz_pages/${cbzFile.absolutePath.hashCode().toString(16)}-$fingerprint",
        )
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val completeMarker = File(cacheDir, ".complete")

        if (completeMarker.exists()) {
            val existing = cacheDir.listFiles { _, name ->
                val n = name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
            }
                ?.sortedBy { it.name.substringBeforeLast(".").filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                ?.map { "file://${it.absolutePath}" }
            if (!existing.isNullOrEmpty()) return existing
        }

        return try {
            val pages = ZipFile(cbzFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        val n = entry.name.lowercase()
                        n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                    }
                    .sortedBy { it.name.substringAfterLast('/').substringBeforeLast(".").filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    .mapIndexed { index, entry ->
                        val ext = entry.name.substringAfterLast('.', "jpg")
                        val outFile = File(cacheDir, String.format("%03d.%s", index + 1, ext))
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        "file://${outFile.absolutePath}"
                    }
                    .toList()
            }
            if (pages.isEmpty()) {
                cacheDir.deleteRecursively()
                emptyList()
            } else {
                completeMarker.writeText("ok")
                pages
            }
        } catch (e: Exception) {
            cacheDir.deleteRecursively()
            emptyList()
        }
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val repository: WebtoonRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
