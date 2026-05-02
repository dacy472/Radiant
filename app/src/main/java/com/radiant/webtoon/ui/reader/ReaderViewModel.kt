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
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Headers
import java.io.File

class ReaderViewModel(
    application: Application,
    private val repository: WebtoonRepository
) : AndroidViewModel(application) {
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

    fun setReadingMode(mode: Int) { _readingMode.value = mode.coerceIn(0, 2) }
    fun setBackgroundColor(color: Color) { _backgroundColor.value = color }
    fun setCropBorders(crop: Boolean) { _cropBorders.value = crop }
    // ────────────────────────────────────────────────────────────────────────

    fun loadChapter(sourceId: String, webtoonId: String, chapterId: String, chapterTitle: String) {
        currentSourceId = sourceId
        currentWebtoonId = webtoonId
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
                // Reach into the underlying HttpSource for its headers
                val httpSource = source.javaClass.getDeclaredField("tachiyomiSource")
                    .also { it.isAccessible = true }
                    .get(source)
                if (httpSource is HttpSource) {
                    _sourceHeaders.value = httpSource.headers
                }
            }
            try {
                // Record Progress
                repository.updateLastPageRead(chapterId, webtoonId, 0)
                try {
                    val detail = repository.getWebtoonDetail(sourceId, webtoonId)
                    repository.markChapterReadLog(
                        com.radiant.webtoon.core.database.History(
                            chapter_id = chapterId,
                            webtoon_id = webtoonId,
                            source_id = sourceId,
                            chapter_title = chapterTitle,
                            webtoon_title = detail.title,
                            webtoon_thumbnail = detail.thumbnail
                        )
                    )
                } catch (e: Exception) {
                    repository.markChapterRead(chapterId, webtoonId, true)
                }
                
                // Check for local download first
                val download = repository.getDownloadTask(chapterId, sourceId)
                if (download?.status == com.radiant.webtoon.core.database.DownloadStatus.COMPLETED && download.localPath != null) {
                    val localPath = download.localPath
                    
                    if (localPath.startsWith("content://")) {
                        // SAF custom folder
                        val docFile = DocumentFile.fromTreeUri(getApplication(), Uri.parse(localPath))
                        if (docFile != null && docFile.isDirectory) {
                            val files = docFile.listFiles()
                                .filter { it.name?.endsWith(".jpg") == true || it.name?.endsWith(".png") == true }
                                .sortedBy { it.name?.substringBefore(".")?.toIntOrNull() ?: 0 }
                                .map { it.uri.toString() }
                                
                            if (files.isNotEmpty()) {
                                _pages.value = files
                                _isLoading.value = false
                                return@launch
                            }
                        }
                    } else {
                        // Legacy regular file folder
                        val dir = File(localPath)
                        if (dir.exists()) {
                            val files = dir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
                                ?.sortedBy { it.name.substringBefore(".").toIntOrNull() ?: 0 }
                                ?.map { "file://${it.absolutePath}" }
                            
                            if (!files.isNullOrEmpty()) {
                                _pages.value = files
                                _isLoading.value = false
                                return@launch
                            }
                        }
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
