package com.radiant.webtoon.core.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.settings.SettingsManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class DownloadQueueManager(
    private val context: Context,
    private val repository: WebtoonRepository,
    private val settingsManager: SettingsManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val networkHelper: NetworkHelper by injectLazy()

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads

    private val _downloadSpeed = MutableStateFlow("0 KB/s")
    val downloadSpeed: StateFlow<String> = _downloadSpeed

    private var isQueueRunning = false
    private var cancelCurrent = false

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

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

    data class DownloadRequest(
        val chapterId: String,
        val sourceId: String,
        val webtoonId: String,
        val webtoonTitle: String,
        val chapterTitle: String
    )

    fun addDownload(chapterId: String, sourceId: String, webtoonId: String, webtoonTitle: String, chapterTitle: String) {
        addDownloads(
            listOf(
                DownloadRequest(
                    chapterId = chapterId,
                    sourceId = sourceId,
                    webtoonId = webtoonId,
                    webtoonTitle = webtoonTitle,
                    chapterTitle = chapterTitle
                )
            )
        )
    }

    /**
     * Enqueue many chapters in one pass with contiguous [DownloadTask.orderIndex]
     * so the queue stays in the caller's order (e.g. chapter 1 → last).
     */
    fun addDownloads(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            var nextIndex = repository.getActiveDownloads().size
            for (req in requests) {
                val existing = repository.getDownloadTask(req.chapterId, req.sourceId)
                if (existing == null) {
                    repository.addDownload(
                        DownloadTask(
                            chapterId = req.chapterId,
                            sourceId = req.sourceId,
                            webtoonId = req.webtoonId,
                            webtoonTitle = req.webtoonTitle,
                            chapterTitle = req.chapterTitle,
                            orderIndex = nextIndex++
                        )
                    )
                } else if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) {
                    repository.updateDownload(existing.copy(status = DownloadStatus.PENDING, orderIndex = nextIndex++))
                } else if (existing.status == DownloadStatus.COMPLETED) {
                    // Already downloaded — skip
                }
                // PENDING / DOWNLOADING — already queued; leave as-is
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
            // Delete any partially/fully downloaded files so cancelling never leaves orphans.
            repository.deleteDownloadFiles(task)
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
                if (settingsManager.downloadOnlyOnWifi.firstOrNull() == true && !isOnWifi()) {
                    Log.d(TAG, "WiFi-only enabled; pausing queue until unmetered connection")
                    isQueueRunning = false
                    break
                }

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
        if (settingsManager.downloadOnlyOnWifi.firstOrNull() == true && !isOnWifi()) {
            Log.d(TAG, "Not on WiFi; leaving ${task.chapterTitle} pending")
            repository.updateDownload(task.copy(status = DownloadStatus.PENDING))
            return
        }

        repository.updateDownload(task.copy(status = DownloadStatus.DOWNLOADING, progress = 0))
        refreshQueueState()

        try {
            val pages = repository.getChapterPages(task.sourceId, task.chapterId)
            if (pages.isEmpty()) {
                repository.updateDownload(task.copy(status = DownloadStatus.FAILED))
                return
            }
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
                            val bytes = downloadImageToUriMeasuring(url, file.uri, task.sourceId)
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
                        val bytes = downloadImageMeasuring(url, targetFile, task.sourceId)
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

    /**
     * Resolves the source's Referer/User-Agent headers for hotlink-protected page images —
     * same requirement as covers (see WebtoonComponents.rememberSourceImageHeaders).
     */
    private fun headersFor(sourceId: String): Map<String, String> {
        val source = repository.extensionManager.getSource(sourceId) ?: return emptyMap()
        val headers = source.getWebViewHeaders().toMutableMap()
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) } && source.baseUrl.isNotBlank()) {
            headers["Referer"] = source.baseUrl.trimEnd('/') + "/"
        }
        return headers
    }

    private fun buildImageRequest(url: String, sourceId: String): Request {
        val builder = Request.Builder().url(url)
        headersFor(sourceId).forEach { (key, value) -> builder.addHeader(key, value) }
        return builder.build()
    }

    private fun downloadImageToUriMeasuring(url: String, uri: Uri, sourceId: String): Long {
        var totalBytes = 0L
        val request = buildImageRequest(url, sourceId)
        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("Empty body")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input ->
                    totalBytes = input.copyTo(output)
                }
            } ?: throw Exception("Failed to open output stream")
        }
        return totalBytes
    }

    private fun downloadImageMeasuring(url: String, file: File, sourceId: String): Long {
        var totalBytes = 0L
        val request = buildImageRequest(url, sourceId)
        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("Empty body")
            FileOutputStream(file).use { output ->
                body.byteStream().use { input ->
                    totalBytes = input.copyTo(output)
                }
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

    companion object {
        private const val TAG = "DownloadQueueManager"
    }
}
