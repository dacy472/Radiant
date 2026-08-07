package com.radiant.webtoon.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Fast local index of completed chapter downloads. UI and reader consult this table
 * instead of scanning the filesystem for download status.
 */
@Entity(tableName = "downloaded_chapters", primaryKeys = ["sourceId", "chapterId"])
data class DownloadedChapter(
    val sourceId: String,
    val chapterId: String,
    val webtoonId: String,
    val webtoonTitle: String,
    val chapterTitle: String,
    val sourceName: String,
    val localDirectory: String,
    val pageCount: Int = 0,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSizeBytes: Long = 0L,
)

@Dao
interface DownloadedChapterDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM downloaded_chapters WHERE sourceId = :sourceId AND chapterId = :chapterId)",
    )
    suspend fun isChapterDownloaded(sourceId: String, chapterId: String): Boolean

    @Query("SELECT chapterId FROM downloaded_chapters WHERE sourceId = :sourceId AND webtoonId = :webtoonId")
    suspend fun getDownloadedChapterIds(sourceId: String, webtoonId: String): List<String>

    @Query("SELECT * FROM downloaded_chapters WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun getByChapter(sourceId: String, chapterId: String): DownloadedChapter?

    @Query("SELECT * FROM downloaded_chapters WHERE sourceId = :sourceId AND webtoonId = :webtoonId")
    fun getByWebtoonFlow(sourceId: String, webtoonId: String): Flow<List<DownloadedChapter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: DownloadedChapter)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<DownloadedChapter>)

    @Query("DELETE FROM downloaded_chapters WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun delete(sourceId: String, chapterId: String)

    @Query("DELETE FROM downloaded_chapters WHERE sourceId = :sourceId AND webtoonId = :webtoonId")
    suspend fun deleteByWebtoon(sourceId: String, webtoonId: String)

    @Query("SELECT * FROM downloaded_chapters ORDER BY downloadedAt DESC")
    fun getAllFlow(): Flow<List<DownloadedChapter>>

    @Query("SELECT * FROM downloaded_chapters ORDER BY downloadedAt DESC")
    suspend fun getAll(): List<DownloadedChapter>

    @Query("SELECT DISTINCT sourceId || '|' || webtoonId FROM downloaded_chapters")
    suspend fun getDownloadedWebtoonKeys(): List<String>

    @Query("SELECT COUNT(*) FROM downloaded_chapters")
    suspend fun count(): Int

    @Query("SELECT * FROM downloaded_chapters WHERE sourceId = :sourceId")
    suspend fun getAllBySource(sourceId: String): List<DownloadedChapter>
}
