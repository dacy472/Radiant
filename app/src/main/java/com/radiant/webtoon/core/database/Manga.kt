package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Groups the user's library by source, counting how many titles are saved per source. */
data class MigrationSourceCount(
    val sourceId: String,
    val count: Int
)

@Entity(tableName = "mangas", primaryKeys = ["url", "source"])
data class Manga(
    val url: String,
    val source: String,
    val title: String,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: String? = null,
    val thumbnail_url: String? = null,
    val initialized: Boolean = false,
    val favorite: Boolean = false,
    val date_added: Long = 0,
    val last_update: Long = 0
)

@Dao
interface MangaDao {
    @Query("SELECT * FROM mangas WHERE favorite = 1 ORDER BY title ASC")
    fun getFavoritesFlow(): Flow<List<Manga>>

    @Query("SELECT * FROM mangas WHERE favorite = 1")
    suspend fun getFavorites(): List<Manga>

    @Query("SELECT * FROM mangas WHERE url = :url AND source = :source")
    suspend fun getManga(url: String, source: String): Manga?

    /** Reactive: emits a new value every time this manga row changes in the DB. */
    @Query("SELECT * FROM mangas WHERE url = :url AND source = :source")
    fun getMangaFlow(url: String, source: String): Flow<Manga?>

    /** Returns per-source library counts for the Migrate tab, updates live. */
    @Query("SELECT source AS sourceId, COUNT(*) AS count FROM mangas WHERE favorite = 1 GROUP BY source")
    fun getLibrarySourceCounts(): Flow<List<MigrationSourceCount>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(manga: Manga): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplacing(manga: Manga): Long

    @Update
    suspend fun update(manga: Manga)

    @Transaction
    suspend fun upsertFetchedMetadata(fetched: Manga) {
        val existing = getManga(fetched.url, fetched.source)
        if (existing == null) {
            insert(
                fetched.copy(
                    date_added = fetched.date_added.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ),
            )
        } else {
            update(MangaMetadataMerge.merge(existing, fetched))
        }
    }

    @Delete
    suspend fun delete(manga: Manga)

    @Query("UPDATE mangas SET favorite = :isFavorite, date_added = :date WHERE url = :url AND source = :source")
    suspend fun updateFavorite(url: String, source: String, isFavorite: Boolean, date: Long)

    @Query("SELECT * FROM mangas WHERE source = :sourceId AND favorite = 1")
    fun getMangaBySource(sourceId: String): Flow<List<Manga>>

    @Query("SELECT * FROM mangas WHERE source = :sourceId")
    suspend fun getAllBySource(sourceId: String): List<Manga>

    @Query("DELETE FROM mangas WHERE url = :url AND source = :source")
    suspend fun delete(url: String, source: String)
}
