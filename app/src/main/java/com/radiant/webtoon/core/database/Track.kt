package com.radiant.webtoon.core.database

import androidx.room.*

@Entity(
    tableName = "tracks",
    primaryKeys = ["mangaUrl", "mangaSource", "trackerId"]
)
data class Track(
    val mangaUrl: String,
    val mangaSource: String,
    val trackerId: String = "anilist",
    val remoteId: String,
    val title: String,
    val lastChapter: Double = 0.0,
    val status: String = "READING"
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE mangaUrl = :mangaUrl AND mangaSource = :mangaSource")
    suspend fun getForManga(mangaUrl: String, mangaSource: String): List<Track>

    @Query("SELECT * FROM tracks WHERE mangaUrl = :mangaUrl AND mangaSource = :mangaSource AND trackerId = :trackerId")
    suspend fun getTrack(mangaUrl: String, mangaSource: String, trackerId: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Delete
    suspend fun delete(track: Track)

    @Query("DELETE FROM tracks WHERE mangaUrl = :mangaUrl AND mangaSource = :mangaSource AND trackerId = :trackerId")
    suspend fun deleteByKey(mangaUrl: String, mangaSource: String, trackerId: String)
}
