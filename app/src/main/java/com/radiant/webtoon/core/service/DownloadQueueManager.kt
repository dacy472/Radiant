package com.radiant.webtoon.core.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.roundToInt

class DownloadQueueManager(
    private val context: Context,
    private val repository: WebtoonRepository,
    private val settingsManager: SettingsManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads

    private val _downloadSpeed = MutableStateFlow("0 KB/s")
    val downloadSpeed: StateFlow<String> = _downloadSpeed

    private var isQueueRunning = false
    private var cancelCurrent = false

    init {
        // Start watching the queue
        scope.launch {
            refreshQueueState()
            startQueueIfIdle()
        }
    }

    private suspend fun refreshQueueState() {
        val queue = repository.getActiveDownloads()
        _activeDownloads.value = queue
    }

    fun addDownload(chapterId: String, sourceId: String, webtoonId: String, webtoonTitle: String, chapterTitle: String) {
        scope.launch {
            val existing = repository.getDownloadTask(chapterId, sourceId)
            if (existing == null) {
                val currentQueueSize = repository.getActiveDownloads().size
                repository.addDownload(
                    DownloadTask(
                        chapterId = chapterId,
                        sourceId = sourceId,
                        webtoonId = webtoonId,
                        webtoonTitle = webtoonTitle,
                        chapterTitle = chapterTitle,
                        orderIndex = currentQueueSize
                    )
                )
            } else if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) {
                repository.updateDownload(existing.copy(status = DownloadStatus.PENDING))
            }
            refreshQueueState()
            startQueueIfIdle()
            startService()
        }
    }

    fun pause(chapterId: String, sourceId: String) {
        scope.launch {
            val task = repository.getDownloadTask(chapterId, sourceId) ?: return@launch
            if (task.status == DownloadStatus.DOWNLOADING) {
                cancelCurrent = true
                job?.cancelAndJoin()
            }
            repository.updateDownload(task.copy(status = DownloadStatus.PAUSED))
            refreshQueueState()
            startQueueIfIdle()
        }
    }

    fun resume(chapterId: String, sourceId: String) {
        scope.launch {
            val task = repository.getDownloadTask(chapterId, sourceId) ?: return@launch
            repository.updateDownload(task.copy(status = DownloadStatus.PENDING))
            refreshQueueState()
            startQueueIfIdle()
            startService()
        }
    }

    fun cancel(chapterId: String, sourceId: String) {
        scope.launch {
            val task = repository.getDownloadTask(chapterId, sourceId) ?: return@launch
            if (task.status == DownloadStatus.DOWNLOADING) {
                cancelCurrent = true
                job?.cancelAndJoin()
            }
            repository.deleteDownload(chapterId, sourceId)
            refreshQueueState()
            startQueueIfIdle()
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        // Simple re-ordering logic for the active queue
        scope.launch {
            val list = _activeDownloads.value.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                // Update indexes in DB
                list.forEachIndexed { i, t ->
                    repository.updateDownload(t.copy(orderIndex = i))
                }
                refreshQueueState()
            }
        }
    }

    private fun startQueueIfIdle() {
        if (isQueueRunning) return
        job = scope.launch {
            isQueueRunning = true
            while (isActive) {
                val nextTask = repository.getNextPendingDownload()
                if (nextTask == null) {
                    isQueueRunning = false
                    break
                }
                cancelCurrent = false
                processDownload(nextTask)
                refreshQueueState()
            }
            isQueueRunning = false
            _downloadSpeed.value = "0 KB/s"
            stopService()
        }
    }

    private fun stopService() {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }

    private suspend fun processDownload(task: DownloadTask) {
        repository.updateDownload(task.copy(status = DownloadStatus.DOWNLOADING, progress = 0))
        refreshQueueState()

        try {
            val pages = repository.getChapterPages(task.sourceId, task.chapterId)
            val total = pages.size
            repository.updateDownload(task.copy(status = DownloadStatus.DOWNLOADING, totalPages = total, progress = 0))

            val customUriStr = settingsManager.downloadLocationUri.firstOrNull()
            
            val localPathStr: String = if (customUriStr != null) {
                val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(customUriStr))
                val sourceDir = baseDir?.findFile(task.sourceId) ?: baseDir?.createDirectory(task.sourceId)
                val chapterDir = sourceDir?.findFile(task.chapterId) ?: sourceDir?.createDirectory(task.chapterId)
                if (chapterDir == null) throw Exception("Failed to create SAF directory")

                pages.forEachIndexed { index, url ->
                    if (cancelCurrent || !coroutineContext.isActive) throw CancellationException()
                    
                    val startTime = System.currentTimeMillis()
                    val fileName = "${index + 1}.jpg"
                    var file = chapterDir.findFile(fileName)
                    if (file == null) {
                        file = chapterDir.createFile("image/jpeg", fileName)
                    }
                    if (file != null) {
                        // Check if file is already fully downloaded (size > 0)
                        if (file.length() > 0) {
                            // Skip download
                        } else {
                            val bytes = downloadImageToUriMeasuring(url, file.uri)
                            updateSpeed(bytes, System.currentTimeMillis() - startTime)
                        }
                    }
                    val progress = ((index + 1).toFloat() / total * 100).toInt()
                    repository.updateDownload(task.copy(progress = progress, downloadedPages = index + 1))
                    refreshQueueState()
                    DownloadService.updateNotification(context, "Downloading ${task.chapterTitle} - ${index+1}/$total", progress)
                }
                chapterDir.uri.toString()
            } else {
                val dir = File(context.getExternalFilesDir(null), "downloads/${task.sourceId}/${task.chapterId}")
                if (!dir.exists()) dir.mkdirs()

                pages.forEachIndexed { index, url ->
                    if (cancelCurrent || !coroutineContext.isActive) throw CancellationException()
                    
                    val targetFile = File(dir, "${index + 1}.jpg")
                    if (targetFile.exists() && targetFile.length() > 0) {
                        // Skip download logic
                    } else {
                        val startTime = System.currentTimeMillis()
                        val bytes = downloadImageMeasuring(url, targetFile)
                        updateSpeed(bytes, System.currentTimeMillis() - startTime)
                    }
                    
                    val progress = ((index + 1).toFloat() / total * 100).toInt()
                    repository.updateDownload(task.copy(progress = progress, downloadedPages = index + 1))
                    refreshQueueState()
                    DownloadService.updateNotification(context, "Downloading ${task.chapterTitle} - ${index+1}/$total", progress)
                }
                dir.absolutePath
            }

            if (!cancelCurrent) {
                repository.updateDownload(task.copy(status = DownloadStatus.COMPLETED, progress = 100, localPath = localPathStr))
                DownloadService.showCompletionNotification(context, "${task.webtoonTitle} - ${task.chapterTitle} Downloaded")
            }
        } catch (e: CancellationException) {
            // Task was paused or cancelled, leave its state as updated by the caller
        } catch (e: Exception) {
            if (!cancelCurrent) {
                repository.updateDownload(task.copy(status = DownloadStatus.FAILED))
            }
        }
    }

    private fun downloadImageToUriMeasuring(url: String, uri: Uri): Long {
        var totalBytes = 0L
        context.contentResolver.openOutputStream(uri)?.use { output ->
            URL(url).openStream().use { input ->
                totalBytes = input.copyTo(output)
            }
        }
        return totalBytes
    }

    private fun downloadImageMeasuring(url: String, file: File): Long {
        var totalBytes = 0L
        URL(url).openStream().use { input ->
            FileOutputStream(file).use { output ->
                totalBytes = input.copyTo(output)
            }
        }
        return totalBytes
    }

    private fun updateSpeed(bytes: Long, timeMs: Long) {
        if (timeMs > 0) {
            val speedKBps = (bytes / 1024f) / (timeMs / 1000f)
            val format = if (speedKBps > 1024) String.format("%.2f MB/s", speedKBps / 1024) else "${speedKBps.roundToInt()} KB/s"
            _downloadSpeed.value = format
        }
    }

    private fun startService() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
