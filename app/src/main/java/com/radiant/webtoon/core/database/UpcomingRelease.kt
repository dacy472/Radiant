package com.radiant.webtoon.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "upcoming_releases")
data class UpcomingRelease(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mangaUrl: String,
    val mangaTitle: String,
    val sourceId: String,
    val chapterName: String,
    val releaseAt: Long,
)

@Dao
interface UpcomingReleaseDao {
    @Query("SELECT * FROM upcoming_releases ORDER BY releaseAt ASC")
    suspend fun getAll(): List<UpcomingRelease>

    @Query("DELETE FROM upcoming_releases")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<UpcomingRelease>)
}
