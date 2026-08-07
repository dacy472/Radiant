package com.radiant.webtoon.core.database

import androidx.room.*

@Entity(tableName = "downloads", primaryKeys = ["chapterId", "sourceId"])
data class DownloadTask(
    val chapterId: String,
    val sourceId: String,
    val webtoonId: String,
    val webtoonTitle: String,
    val chapterTitle: String,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val localPath: String? = null,
    val orderIndex: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY orderIndex ASC, dateAdded ASC")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<DownloadTask>>

    @Query("SELECT * FROM downloads ORDER BY orderIndex ASC, dateAdded ASC")
    suspend fun getAll(): List<DownloadTask>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'PENDING' OR status = 'PAUSED' ORDER BY orderIndex ASC, dateAdded ASC")
    suspend fun getActiveQueue(): List<DownloadTask>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'PENDING' ORDER BY orderIndex ASC, dateAdded ASC LIMIT 1")
    suspend fun getNextPending(): DownloadTask?

    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY orderIndex ASC, dateAdded ASC LIMIT 1")
    suspend fun getNextPendingOnly(): DownloadTask?

    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY orderIndex ASC, dateAdded ASC LIMIT :limit")
    suspend fun getPendingLimit(limit: Int): List<DownloadTask>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'DOWNLOADING'")
    suspend fun countDownloading(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING', 'PAUSED'))")
    suspend fun hasActiveWork(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTask)

    @Update
    suspend fun update(task: DownloadTask)

    @Query("SELECT * FROM downloads WHERE chapterId = :chapterId AND sourceId = :sourceId")
    suspend fun getByChapter(chapterId: String, sourceId: String): DownloadTask?

    @Query("DELETE FROM downloads WHERE chapterId = :chapterId AND sourceId = :sourceId")
    suspend fun delete(chapterId: String, sourceId: String)

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY webtoonTitle ASC, dateAdded DESC")
    suspend fun getCompleted(): List<DownloadTask>
}
