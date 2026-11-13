package com.radiant.webtoon.core.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.image.ImageFetchRetry
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.DownloadService
import com.radiant.webtoon.core.settings.SettingsManager
import com.radiant.webtoon.core.source.PageRefs
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.io.File
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
        var current = task.copy(status = DownloadStatus.DOWNLOADING, progress = 0)
        return try {
            val source = webtoonRepository.extensionManager.getSource(task.sourceId)
                ?: throw IllegalStateException("source-not-found")
            val pages = repository.getChapterPages(task.sourceId, task.chapterId, task.webtoonId)
            if (pages.isEmpty()) {
                throw IllegalStateException("empty-pages")
            }

            val sourceName = source.name
            val total = pages.size
            current = current.copy(totalPages = total, downloadedPages = 0)
            repository.updateDownload(current)
            onProgress(current)

            val customUriStr = settingsManager.downloadLocationUri.firstOrNull()
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
            sourceId = task.sourceId,
            mangaId = task.webtoonId,
            chapterId = task.chapterId,
            sourceName = sourceName,
            mangaTitle = task.webtoonTitle,
            chapterTitle = task.chapterTitle,
        )
        if (!dir.exists()) dir.mkdirs()

        pages.forEachIndexed { index, url ->
            if (isCancelled() || !coroutineContext.isActive) throw CancellationException()
            val targetFile = File(dir, DownloadPathUtils.pageFileName(index))
            if (!(targetFile.exists() && ImagePayloadValidator.isImage(targetFile))) {
                val tmp = File(dir, DownloadPathUtils.pageFileName(index) + ".part")
                val startTime = System.currentTimeMillis()
                val bytes = downloadImageWithRetry(url, task.sourceId) { output ->
                    tmp.outputStream().use { fileOut -> output.copyTo(fileOut) }
                }
                if (!ImagePayloadValidator.isImage(tmp)) {
                    tmp.delete()
                    throw IllegalStateException("invalid image payload")
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    tmp.delete()
                    throw IllegalStateException("failed to replace page file")
                }
                if (!tmp.renameTo(targetFile)) {
                    tmp.delete()
                    throw IllegalStateException("failed to commit page file")
                }
                updateSpeed(bytes, System.currentTimeMillis() - startTime)
            }
            updateTaskProgress(task, index + 1, pages.size)
        }
        val verified = pages.indices.all { index ->
            ImagePayloadValidator.isImage(File(dir, DownloadPathUtils.pageFileName(index)))
        }
        if (!verified) throw IllegalStateException("incomplete chapter pages")
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
            sourceId = task.sourceId,
            mangaId = task.webtoonId,
            chapterId = task.chapterId,
            sourceName = sourceName,
            mangaTitle = task.webtoonTitle,
            chapterTitle = task.chapterTitle,
        )
        val chapterDir = ensureSafDirectory(baseDir, relative.split(File.separator))
            ?: throw IllegalStateException("Failed to create SAF directory")

        pages.forEachIndexed { index, url ->
            if (isCancelled() || !coroutineContext.isActive) throw CancellationException()
            val fileName = DownloadPathUtils.pageFileName(index)
            val partName = "$fileName.part"
            chapterDir.findFile(partName)?.delete()
            var file = chapterDir.findFile(fileName)
            val existingValid = file != null && file.length() > 12L
            if (!existingValid) {
                file?.delete()
                file = chapterDir.createFile("image/jpeg", fileName)
                    ?: throw IllegalStateException("createFile returned null")
                val startTime = System.currentTimeMillis()
                val bytes = downloadImageWithRetry(url, task.sourceId) { output ->
                    context.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
                        output.copyTo(out)
                    } ?: throw IllegalStateException("Failed to open SAF output stream")
                }
                val header = ByteArray(16)
                val read = context.contentResolver.openInputStream(file.uri)?.use { it.read(header) } ?: 0
                if (!ImagePayloadValidator.isImage(header.copyOf(read.coerceAtLeast(0)))) {
                    file.delete()
                    throw IllegalStateException("invalid image payload")
                }
                updateSpeed(bytes, System.currentTimeMillis() - startTime)
            }
            updateTaskProgress(task, index + 1, pages.size)
        }
        val verified = pages.indices.all { index ->
            val file = chapterDir.findFile(DownloadPathUtils.pageFileName(index))
            file != null && file.length() > 12L
        }
        if (!verified) throw IllegalStateException("incomplete chapter pages")
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
    ): Long = ImageFetchRetry.run(MAX_IMAGE_RETRY_ATTEMPTS) {
        // Same path as the reader: the extension's own client + imageRequest (Mihon parity).
        val adapter = webtoonRepository.extensionManager.getSource(sourceId) as? TachiyomiSourceAdapter
        val response = adapter?.openPageImage(url)
            ?: if (PageRefs.isDeferred(url)) {
                throw IllegalStateException("Source $sourceId cannot resolve this page")
            } else {
                withContext(Dispatchers.IO) {
                    networkHelper.client.newCall(buildImageRequest(url, sourceId)).execute()
                }.also { response ->
                    if (!response.isSuccessful) {
                        response.close()
                        throw java.io.IOException("HTTP ${response.code}")
                    }
                }
            }
        response.use {
            var totalBytes = 0L
            withContext(Dispatchers.IO) {
                it.body.byteStream().use { input ->
                    writeBody(object : java.io.FilterInputStream(input) {
                        override fun read(): Int {
                            val value = super.read()
                            if (value >= 0) totalBytes++
                            return value
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val count = super.read(b, off, len)
                            if (count > 0) totalBytes += count
                            return count
                        }
                    })
                }
            }
            totalBytes
        }
    }

    private fun headersFor(sourceId: String): Headers {
        val source = webtoonRepository.extensionManager.getSource(sourceId)
            ?: return Headers.headersOf()
        return com.radiant.webtoon.core.image.SourceImageHeaders.from(source)
    }

    private fun buildImageRequest(url: String, sourceId: String): Request {
        return Request.Builder().url(url).headers(headersFor(sourceId)).build()
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
        const val MAX_IMAGE_RETRY_ATTEMPTS = ImageFetchRetry.MAX_ATTEMPTS
    }
}
