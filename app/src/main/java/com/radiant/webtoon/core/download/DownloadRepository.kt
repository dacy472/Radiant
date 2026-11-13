package com.radiant.webtoon.core.download

import androidx.room.withTransaction
import com.radiant.webtoon.core.database.DownloadDao
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.database.DownloadedChapterDao
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Persistence layer for the download queue (Room-backed).
 */
class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val downloadedChapterDao: DownloadedChapterDao,
    private val webtoonRepository: WebtoonRepository,
    private val database: WebtoonDatabase,
) {
    val downloadsFlow: Flow<List<DownloadTask>> = downloadDao.getAllFlow()

    suspend fun getActiveDownloads(): List<DownloadTask> = withContext(Dispatchers.IO) {
        downloadDao.getActiveQueue()
    }

    suspend fun getAllDownloads(): List<DownloadTask> = withContext(Dispatchers.IO) {
        downloadDao.getAll()
    }

    suspend fun getDownloadTask(chapterId: String, sourceId: String): DownloadTask? =
        withContext(Dispatchers.IO) {
            downloadDao.getByChapter(chapterId, sourceId)
        }

    suspend fun addDownload(task: DownloadTask) = withContext(Dispatchers.IO) {
        downloadDao.insert(task)
    }

    suspend fun updateDownload(task: DownloadTask) = withContext(Dispatchers.IO) {
        downloadDao.update(task)
    }

    suspend fun deleteDownload(chapterId: String, sourceId: String) = withContext(Dispatchers.IO) {
        downloadDao.delete(chapterId, sourceId)
        downloadedChapterDao.delete(sourceId, chapterId)
    }

    suspend fun registerDownloadedChapter(
        sourceId: String,
        chapterId: String,
        webtoonId: String,
        webtoonTitle: String,
        chapterTitle: String,
        sourceName: String,
        localDirectory: String,
        pageCount: Int,
        fileSizeBytes: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        downloadedChapterDao.insert(
            DownloadedChapter(
                sourceId = sourceId,
                chapterId = chapterId,
                webtoonId = webtoonId,
                webtoonTitle = webtoonTitle,
                chapterTitle = chapterTitle,
                sourceName = sourceName,
                localDirectory = localDirectory,
                pageCount = pageCount,
                fileSizeBytes = fileSizeBytes,
            ),
        )
    }

    suspend fun isChapterDownloaded(sourceId: String, chapterId: String): Boolean =
        withContext(Dispatchers.IO) {
            downloadedChapterDao.isChapterDownloaded(sourceId, chapterId)
        }

    suspend fun getDownloadedChapter(sourceId: String, chapterId: String): DownloadedChapter? =
        withContext(Dispatchers.IO) {
            downloadedChapterDao.getByChapter(sourceId, chapterId)
        }

    fun downloadedChaptersFlow(): Flow<List<DownloadedChapter>> = downloadedChapterDao.getAllFlow()

    fun downloadedChaptersForWebtoonFlow(sourceId: String, webtoonId: String) =
        downloadedChapterDao.getByWebtoonFlow(sourceId, webtoonId)

    suspend fun deleteDownloadFiles(task: DownloadTask) = withContext(Dispatchers.IO) {
        webtoonRepository.deleteDownloadFiles(task)
    }

    suspend fun getChapterPages(sourceId: String, chapterId: String, mangaId: String? = null): List<String> =
        webtoonRepository.getChapterPages(sourceId, chapterId, mangaId)

    suspend fun getDownloading(): List<DownloadTask> = withContext(Dispatchers.IO) {
        downloadDao.getDownloading()
    }

    suspend fun recoverOrphanedDownloading(hasInMemoryJob: (DownloadTask) -> Boolean) = withContext(Dispatchers.IO) {
        database.withTransaction {
            downloadDao.getDownloading().forEach { task ->
                val recovered = DownloadQueuePolicy.recoveredStatus(task, hasInMemoryJob(task))
                if (recovered != task.status) {
                    downloadDao.update(task.copy(status = recovered))
                }
            }
        }
    }

    suspend fun getPendingTasks(limit: Int): List<DownloadTask> = withContext(Dispatchers.IO) {
        downloadDao.getPendingLimit(limit)
    }

    suspend fun hasActiveWork(): Boolean = withContext(Dispatchers.IO) {
        downloadDao.hasActiveWork()
    }

    /**
     * Atomically marks the next FIFO pending task as downloading so concurrent workers
     * never claim the same chapter.
     */
    suspend fun claimNextPending(): DownloadTask? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val next = downloadDao.getNextPendingOnly() ?: return@withTransaction null
            val claimed = next.copy(status = DownloadStatus.DOWNLOADING, progress = 0)
            downloadDao.update(claimed)
            claimed
        }
    }
}
