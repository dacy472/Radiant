package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "history",
    primaryKeys = ["source_id", "webtoon_id", "chapter_id"],
)
data class History(
    val chapter_id: String,
    val webtoon_id: String,
    val source_id: String,
    val chapter_title: String,
    val webtoon_title: String,
    val webtoon_thumbnail: String?,
    val read_at: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY read_at DESC")
    fun getAllFlow(): Flow<List<History>>

    @Query("SELECT * FROM history ORDER BY read_at DESC")
    suspend fun getAll(): List<History>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: History)

    @Query("SELECT * FROM history WHERE webtoon_id = :webtoonId AND source_id = :sourceId")
    suspend fun getForWebtoon(webtoonId: String, sourceId: String): List<History>

    @Query("SELECT * FROM history WHERE webtoon_id = :webtoonId AND source_id = :sourceId ORDER BY read_at DESC LIMIT 1")
    fun getLatestForWebtoonFlow(webtoonId: String, sourceId: String): Flow<History?>

    @Query("DELETE FROM history WHERE chapter_id = :chapterId AND webtoon_id = :webtoonId AND source_id = :sourceId")
    suspend fun delete(chapterId: String, webtoonId: String, sourceId: String)

    @Query("DELETE FROM history WHERE webtoon_id = :webtoonId AND source_id = :sourceId")
    suspend fun deleteForWebtoon(webtoonId: String, sourceId: String)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
