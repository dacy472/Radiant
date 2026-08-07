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
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Headers
import java.io.File
import java.util.zip.ZipFile

class ReaderViewModel(
    application: Application,
    private val repository: WebtoonRepository,
) : AndroidViewModel(application) {
    private val settingsManager = getApplication<WebtoonApplication>().settingsManager

    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** HTTP headers from the source (User-Agent, Referer, etc.) for Coil image requests. */
    private val _sourceHeaders = MutableStateFlow(Headers.headersOf())
    val sourceHeaders: StateFlow<Headers> = _sourceHeaders

    // ── Reader Preferences ──────────────────────────────────────────────────
    /** 0 = Vertical (webtoon), 1 = Left-to-Right, 2 = Right-to-Left */
    private val _readingMode = MutableStateFlow(0)
    val readingMode: StateFlow<Int> = _readingMode

    private val _backgroundColor = MutableStateFlow(Color.Black)
    val backgroundColor: StateFlow<Color> = _backgroundColor

    private val _cropBorders = MutableStateFlow(false)
    val cropBorders: StateFlow<Boolean> = _cropBorders

    private val _nextChapter = MutableStateFlow<Chapter?>(null)
    val nextChapter: StateFlow<Chapter?> = _nextChapter

    private val _previousChapter = MutableStateFlow<Chapter?>(null)
    val previousChapter: StateFlow<Chapter?> = _previousChapter

    private var currentSourceId: String = ""
    private var currentWebtoonId: String = ""
    var currentChapterId: String = ""
        private set

    init {
        viewModelScope.launch {
            settingsManager.readingDirection.collect { direction ->
                _readingMode.value = when (direction) {
                    "ltr" -> 1
                    "rtl" -> 2
                    else -> 0
                }
            }
        }
    }

    fun setReadingMode(mode: Int) {
        val coerced = mode.coerceIn(0, 2)
        _readingMode.value = coerced
        viewModelScope.launch {
            val direction = when (coerced) {
                1 -> "ltr"
                2 -> "rtl"
                else -> "vertical"
            }
            settingsManager.setReadingDirection(direction)
        }
    }

    fun setBackgroundColor(color: Color) { _backgroundColor.value = color }
    fun setCropBorders(crop: Boolean) { _cropBorders.value = crop }

    fun markChapterReadWhenComplete(chapterId: String, webtoonId: String) {
        viewModelScope.launch {
            repository.markChapterRead(chapterId, webtoonId, true)

            // Auto-delete downloaded chapter if enabled
            if (settingsManager.autoDeleteRead.first()) {
                if (repository.isChapterDownloaded(currentSourceId, chapterId)) {
                    repository.deleteDownloadedChapter(chapterId, currentSourceId)
                }
            }

            // Sync AniList progress when tracked
            try {
                val app = getApplication<WebtoonApplication>()
                val track = app.database.trackDao()
                    .getForManga(webtoonId, currentSourceId)
                    .firstOrNull { it.trackerId == "anilist" }
                if (track != null) {
                    val chapters = repository.getChaptersForManga(webtoonId)
                    val chapter = chapters.find { it.url == chapterId }
                    val number = chapter?.chapter_number?.toInt()?.coerceAtLeast(0) ?: 0
                    if (number > 0) {
                        app.trackerService.syncProgressChecked(track.remoteId, number)
                        app.database.trackDao().insert(track.copy(lastChapter = number.toDouble()))
                    }
                }
            } catch (_: Exception) {
                // Tracking is best-effort
            }
        }
    }
    // ────────────────────────────────────────────────────────────────────────

    fun loadChapter(sourceId: String, webtoonId: String, chapterId: String, chapterTitle: String) {
        currentSourceId = sourceId
        currentWebtoonId = webtoonId
        currentChapterId = chapterId
        _isLoading.value = true
        
        viewModelScope.launch {
            // Fetch adjacent chapters to identify "Next"
            try {
                val allChapters = repository.chapterDao.getChapters(webtoonId)
                val currentIndex = allChapters.indexOfFirst { it.url == chapterId }
                
                if (currentIndex != -1) {
                    _nextChapter.value = allChapters.getOrNull(currentIndex - 1)
                    _previousChapter.value = allChapters.getOrNull(currentIndex + 1)
                } else {
                    _nextChapter.value = null
                    _previousChapter.value = null
                }
            } catch (e: Exception) {
                _nextChapter.value = null
                _previousChapter.value = null
            }

            // Resolve source headers so Coil can attach them to image requests
            val app = getApplication<WebtoonApplication>()
            val source = app.extensionManager.getSource(sourceId)
            if (source is TachiyomiSourceAdapter) {
                _sourceHeaders.value = source.httpHeaders
            }
            try {
                // Record history / last-opened progress without marking the chapter fully read
                repository.updateLastPageRead(chapterId, webtoonId, 0)
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
                        // History write is best-effort
                    }
                }

                // Check indexed local download first (no filesystem scan)
                val downloaded = repository.getDownloadedChapter(sourceId, chapterId)
                if (downloaded != null) {
                    val localPages = loadLocalPages(downloaded.localDirectory)
                    if (localPages.isNotEmpty()) {
                        _pages.value = localPages
                        _isLoading.value = false
                        return@launch
                    }
                }

                // Fallback to network
                val pages = repository.getChapterPages(sourceId, chapterId)
                _pages.value = pages
            } catch (e: Exception) {
                _pages.value = emptyList()
            }
            _isLoading.value = false
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
        val cacheDir = File(getApplication<Application>().cacheDir, "cbz_pages/${cbzFile.nameWithoutExtension}")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // Reuse extracted pages when present
        val existing = cacheDir.listFiles { _, name ->
            val n = name.lowercase()
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
        }
            ?.sortedBy { it.name.substringBeforeLast(".").filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            ?.map { "file://${it.absolutePath}" }
        if (!existing.isNullOrEmpty()) return existing

        return try {
            ZipFile(cbzFile).use { zip ->
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
                        if (!outFile.exists() || outFile.length() == 0L) {
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        "file://${outFile.absolutePath}"
                    }
                    .toList()
            }
        } catch (e: Exception) {
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
