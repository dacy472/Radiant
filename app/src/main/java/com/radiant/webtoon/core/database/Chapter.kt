package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chapters",
    primaryKeys = ["url", "manga_id"],
    indices = [Index(value = ["manga_id"])]
)
data class Chapter(
    val url: String,
    val manga_id: String, // Maps to Manga.url
    val name: String,
    val scanlator: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val last_page_read: Int = 0,
    val date_fetch: Long = 0,
    val date_upload: Long = 0,
    val chapter_number: Float = -1f,
    val source: String
)

data class UpdateItem(
    @Embedded val chapter: Chapter,
    val webtoonTitle: String,
    val webtoonThumbnail: String?,
    val sourceId: String
)

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY chapter_number DESC")
    fun getChaptersFlow(mangaId: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters")
    fun getAllChaptersFlow(): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY chapter_number DESC")
    suspend fun getChapters(mangaId: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE url = :url AND manga_id = :mangaId")
    suspend fun getChapter(url: String, mangaId: String): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<Chapter>)

    @Update
    suspend fun update(chapter: Chapter)

    @Query("UPDATE chapters SET read = :isRead WHERE url = :url AND manga_id = :mangaId")
    suspend fun updateRead(url: String, mangaId: String, isRead: Boolean)

    @Query("UPDATE chapters SET last_page_read = :lastPage WHERE url = :url AND manga_id = :mangaId")
    suspend fun updateLastPageRead(url: String, mangaId: String, lastPage: Int)

    @Query("DELETE FROM chapters WHERE manga_id = :mangaId")
    suspend fun deleteByManga(mangaId: String)

    @Query("""
        SELECT chapters.*, mangas.title AS webtoonTitle, mangas.thumbnail_url AS webtoonThumbnail, mangas.source AS sourceId 
        FROM chapters 
        INNER JOIN mangas ON chapters.manga_id = mangas.url AND chapters.source = mangas.source
        WHERE mangas.favorite = 1 AND chapters.date_fetch > 0 
        ORDER BY chapters.date_fetch DESC 
        LIMIT 100
    """)
    fun getRecentUpdates(): Flow<List<UpdateItem>>
}
