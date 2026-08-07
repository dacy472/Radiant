package com.radiant.webtoon.core.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.DownloadService
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Singleton download orchestrator: FIFO queue, max [MAX_CONCURRENT_DOWNLOADS] in parallel,
 * retries per page, structured local paths.
 */
class DownloadManager(
    private val context: Context,
    private val webtoonRepository: WebtoonRepository,
    private val settingsManager: SettingsManager,
    parentScope: CoroutineScope,
) {
    private val downloadRepository = DownloadRepository(
        webtoonRepository.database.downloadDao(),
        webtoonRepository.database.downloadedChapterDao(),
        webtoonRepository,
        webtoonRepository.database,
    )
    private val downloadQueue = DownloadQueue()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val concurrencyLimiter = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private val chapterWorker = ChapterDownloadWorker(
        context = context,
        repository = downloadRepository,
        webtoonRepository = webtoonRepository,
        settingsManager = settingsManager,
        onProgress = { scope.launch { refreshQueueState() } },
        onSpeedUpdate = { speed -> _downloadSpeed.value = speed },
    )

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    /** Backward-compatible alias used by existing UI. */
    val activeDownloads: StateFlow<List<DownloadTask>> = downloads

    private val _downloadSpeed = MutableStateFlow("0 KB/s")
    val downloadSpeed: StateFlow<String> = _downloadSpeed.asStateFlow()

    private var processorJob: Job? = null
    @Volatile
    private var cancelFlags = mutableSetOf<String>()

    init {
        scope.launch {
            refreshQueueState()
            ensureProcessorRunning()
        }
    }

    data class DownloadRequest(
        val chapterId: String,
        val sourceId: String,
        val webtoonId: String,
        val webtoonTitle: String,
        val chapterTitle: String,
    )

    fun addDownload(
        chapterId: String,
        sourceId: String,
        webtoonId: String,
        webtoonTitle: String,
        chapterTitle: String,
    ) {
        addDownloads(
            listOf(
                DownloadRequest(
                    chapterId = chapterId,
                    sourceId = sourceId,
                    webtoonId = webtoonId,
                    webtoonTitle = webtoonTitle,
                    chapterTitle = chapterTitle,
                ),
            ),
        )
    }

    fun addDownloads(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            var nextIndex = downloadRepository.getActiveDownloads().size
            for (req in requests) {
                val existing = downloadRepository.getDownloadTask(req.chapterId, req.sourceId)
                when {
                    existing == null -> {
                        downloadRepository.addDownload(
                            DownloadTask(
                                chapterId = req.chapterId,
                                sourceId = req.sourceId,
                                webtoonId = req.webtoonId,
                                webtoonTitle = req.webtoonTitle,
                                chapterTitle = req.chapterTitle,
                                orderIndex = nextIndex++,
                            ),
                        )
                    }
                    existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED -> {
                        downloadRepository.updateDownload(
                            existing.copy(status = DownloadStatus.PENDING, orderIndex = nextIndex++),
                        )
                    }
                    existing.status == DownloadStatus.COMPLETED -> Unit
                    else -> Unit
                }
            }
            refreshQueueState()
            ensureProcessorRunning()
            startService()
        }
    }

    fun pause(chapterId: String, sourceId: String) {
        scope.launch {
            val task = downloadRepository.getDownloadTask(chapterId, sourceId) ?: return@launch
            if (task.status == DownloadStatus.DOWNLOADING) {
                setCancelled(task, true)
                downloadQueue.cancelJob(task)
            }
            downloadRepository.updateDownload(task.copy(status = DownloadStatus.PAUSED))
            refreshQueueState()
            ensureProcessorRunning()
        }
    }

    fun resume(chapterId: String, sourceId: String) {
        scope.launch {
            val task = downloadRepository.getDownloadTask(chapterId, sourceId) ?: return@launch
            setCancelled(task, false)
            downloadRepository.updateDownload(task.copy(status = DownloadStatus.PENDING))
            refreshQueueState()
            ensureProcessorRunning()
            startService()
        }
    }

    fun cancel(chapterId: String, sourceId: String) {
        scope.launch {
            val task = downloadRepository.getDownloadTask(chapterId, sourceId) ?: return@launch
            if (task.status == DownloadStatus.DOWNLOADING) {
                setCancelled(task, true)
                downloadQueue.cancelJob(task)
            }
            downloadRepository.deleteDownloadFiles(task)
            downloadRepository.deleteDownload(chapterId, sourceId)
            refreshQueueState()
            ensureProcessorRunning()
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        scope.launch {
            val list = _downloads.value.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                list.forEachIndexed { index, task ->
                    downloadRepository.updateDownload(task.copy(orderIndex = index))
                }
                refreshQueueState()
            }
        }
    }

    private fun ensureProcessorRunning() {
        if (processorJob?.isActive == true) return
        processorJob = scope.launch {
            processQueueLoop()
        }
    }

    private suspend fun processQueueLoop() {
        while (true) {
            if (settingsManager.downloadOnlyOnWifi.firstOrNull() == true && !isOnWifi()) {
                Log.d(TAG, "WiFi-only enabled; waiting for unmetered connection")
                delay(POLL_INTERVAL_MS)
                continue
            }

            val downloadingCount = downloadRepository.countDownloading()
            val slots = MAX_CONCURRENT_DOWNLOADS - downloadingCount
            if (slots > 0) {
                repeat(slots) {
                    val claimed = downloadRepository.claimNextPending() ?: return@repeat
                    launchChapterDownload(claimed)
                }
            }

            refreshQueueState()

            val stillWorking = downloadRepository.hasActiveWork() || downloadQueue.activeCount() > 0
            if (!stillWorking) {
                _downloadSpeed.value = "0 KB/s"
                stopService()
                break
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun launchChapterDownload(task: DownloadTask) {
        val key = downloadQueue.taskKey(task)
        setCancelled(task, false)
        val job = scope.launch {
            concurrencyLimiter.withPermit {
                try {
                    chapterWorker.download(task) { isCancelled(task) }
                } catch (_: CancellationException) {
                    // Pause/cancel handled by caller updating DB status.
                } finally {
                    setCancelled(task, false)
                    downloadQueue.unregister(task)
                    ensureProcessorRunning()
                }
            }
        }
        downloadQueue.register(task, job)
    }

    private suspend fun refreshQueueState() {
        _downloads.value = downloadRepository.getActiveDownloads()
    }

    private fun isCancelled(task: DownloadTask): Boolean {
        return cancelFlags.contains(downloadQueue.taskKey(task))
    }

    private fun setCancelled(task: DownloadTask, cancelled: Boolean) {
        synchronized(cancelFlags) {
            if (cancelled) {
                cancelFlags.add(downloadQueue.taskKey(task))
            } else {
                cancelFlags.remove(downloadQueue.taskKey(task))
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun startService() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }

    companion object {
        private const val TAG = "DownloadManager"
        const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val POLL_INTERVAL_MS = 400L
    }
}
