package com.radiant.webtoon.core.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.DownloadService
import com.radiant.webtoon.core.settings.SettingsManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Downloads all pages for a single chapter to local storage.
 */
class ChapterDownloadWorker(
    private val context: Context,
    private val repository: DownloadRepository,
    private val webtoonRepository: WebtoonRepository,
    private val settingsManager: SettingsManager,
    private val onProgress: suspend (DownloadTask) -> Unit,
    private val onSpeedUpdate: (String) -> Unit,
) {
    private val networkHelper: NetworkHelper by injectLazy()

    suspend fun download(task: DownloadTask, isCancelled: () -> Boolean): DownloadTask {
        val pages = repository.getChapterPages(task.sourceId, task.chapterId)
        if (pages.isEmpty()) {
            val failed = task.copy(status = DownloadStatus.FAILED)
            repository.updateDownload(failed)
            return failed
        }

        val sourceName = webtoonRepository.extensionManager.getSource(task.sourceId)?.name
            ?: task.sourceId
        val total = pages.size
        var current = task.copy(
            status = DownloadStatus.DOWNLOADING,
            totalPages = total,
            progress = 0,
            downloadedPages = 0,
        )
        repository.updateDownload(current)
        onProgress(current)

        val customUriStr = settingsManager.downloadLocationUri.firstOrNull()
        return try {
            val localPath = if (customUriStr != null) {
                downloadToSaf(customUriStr, sourceName, current, pages, isCancelled)
            } else {
                downloadToFiles(sourceName, current, pages, isCancelled)
            }
            if (isCancelled() || !coroutineContext.isActive) {
                throw CancellationException()
            }
            current = current.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                downloadedPages = total,
                localPath = localPath,
            )
            repository.updateDownload(current)
            repository.registerDownloadedChapter(
                sourceId = task.sourceId,
                chapterId = task.chapterId,
                webtoonId = task.webtoonId,
                webtoonTitle = task.webtoonTitle,
                chapterTitle = task.chapterTitle,
                sourceName = sourceName,
                localDirectory = localPath,
                pageCount = total,
                fileSizeBytes = computeLocalSize(localPath),
            )
            onProgress(current)
            DownloadService.showCompletionNotification(
                context,
                "${task.webtoonTitle} - ${task.chapterTitle} Downloaded",
            )
            current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chapter download failed: ${task.chapterTitle}", e)
            current = current.copy(status = DownloadStatus.FAILED)
            repository.updateDownload(current)
            onProgress(current)
            current
        }
    }

    private suspend fun downloadToFiles(
        sourceName: String,
        task: DownloadTask,
        pages: List<String>,
        isCancelled: () -> Boolean,
    ): String {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = DownloadPathUtils.defaultChapterDir(
            baseDir,
            sourceName,
            task.webtoonTitle,
            task.chapterTitle,
        )
        if (!dir.exists()) dir.mkdirs()

        pages.forEachIndexed { index, url ->
            if (isCancelled() || !coroutineContext.isActive) throw CancellationException()
            val targetFile = File(dir, DownloadPathUtils.pageFileName(index))
            if (!(targetFile.exists() && targetFile.length() > 0)) {
                val startTime = System.currentTimeMillis()
                val bytes = downloadImageWithRetry(url, task.sourceId) { output ->
                    FileOutputStream(targetFile).use { fileOut ->
                        output.copyTo(fileOut)
                    }
                }
                updateSpeed(bytes, System.currentTimeMillis() - startTime)
            }
            updateTaskProgress(task, index + 1, pages.size)
        }
        return dir.absolutePath
    }

    private suspend fun downloadToSaf(
        customUriStr: String,
        sourceName: String,
        task: DownloadTask,
        pages: List<String>,
        isCancelled: () -> Boolean,
    ): String {
        val baseDir = DocumentFile.fromTreeUri(context, Uri.parse(customUriStr))
            ?: throw IllegalStateException("Invalid SAF download location")
        val relative = DownloadPathUtils.chapterRelativePath(
            sourceName,
            task.webtoonTitle,
            task.chapterTitle,
        )
        val chapterDir = ensureSafDirectory(baseDir, relative.split(File.separator))
            ?: throw IllegalStateException("Failed to create SAF directory")

        pages.forEachIndexed { index, url ->
            if (isCancelled() || !coroutineContext.isActive) throw CancellationException()
            val fileName = DownloadPathUtils.pageFileName(index)
            var file = chapterDir.findFile(fileName)
            if (file == null) {
                file = chapterDir.createFile("image/jpeg", fileName)
            }
            if (file != null && file.length() == 0L) {
                val startTime = System.currentTimeMillis()
                val bytes = downloadImageWithRetry(url, task.sourceId) { output ->
                    context.contentResolver.openOutputStream(file.uri)?.use { out ->
                        output.copyTo(out)
                    } ?: throw IllegalStateException("Failed to open SAF output stream")
                }
                updateSpeed(bytes, System.currentTimeMillis() - startTime)
            }
            updateTaskProgress(task, index + 1, pages.size)
        }
        return chapterDir.uri.toString()
    }

    private suspend fun updateTaskProgress(task: DownloadTask, downloadedPages: Int, total: Int) {
        val progress = ((downloadedPages.toFloat() / total) * 100).toInt()
        val updated = task.copy(
            status = DownloadStatus.DOWNLOADING,
            totalPages = total,
            downloadedPages = downloadedPages,
            progress = progress,
        )
        repository.updateDownload(updated)
        onProgress(updated)
        DownloadService.updateNotification(
            context,
            "Downloading ${task.chapterTitle} - $downloadedPages/$total",
            progress,
        )
    }

    private fun ensureSafDirectory(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        return current
    }

    private suspend fun downloadImageWithRetry(
        url: String,
        sourceId: String,
        writeBody: (java.io.InputStream) -> Unit,
    ): Long {
        var lastError: Exception? = null
        repeat(MAX_IMAGE_RETRY_ATTEMPTS) { attempt ->
            try {
                val request = buildImageRequest(url, sourceId)
                var totalBytes = 0L
                networkHelper.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw Exception("Empty body")
                    body.byteStream().use { input ->
                        writeBody(input)
                        totalBytes = body.contentLength().coerceAtLeast(0L)
                    }
                }
                return totalBytes
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                if (attempt < MAX_IMAGE_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_BASE_DELAY_MS * (1L shl attempt))
                }
            }
        }
        throw lastError ?: Exception("Image download failed")
    }

    private fun headersFor(sourceId: String): Map<String, String> {
        val source = webtoonRepository.extensionManager.getSource(sourceId) ?: return emptyMap()
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

    private fun updateSpeed(bytes: Long, timeMs: Long) {
        if (timeMs <= 0) return
        val speedKBps = (bytes / 1024f) / (timeMs / 1000f)
        val format = if (speedKBps > 1024) {
            String.format("%.2f MB/s", speedKBps / 1024)
        } else {
            "${speedKBps.roundToInt()} KB/s"
        }
        onSpeedUpdate(format)
    }

    private fun computeLocalSize(localPath: String): Long {
        if (localPath.startsWith("content://")) return 0L
        val file = File(localPath)
        return when {
            file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            file.isFile -> file.length()
            else -> 0L
        }
    }

    companion object {
        private const val TAG = "ChapterDownloadWorker"
        const val MAX_IMAGE_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1_000L
    }
}
