package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "history"
)
data class History(
    @PrimaryKey
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: History)

    @Query("DELETE FROM history WHERE chapter_id = :chapterId")
    suspend fun delete(chapterId: String)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
