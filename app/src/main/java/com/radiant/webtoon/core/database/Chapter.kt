package com.radiant.webtoon.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chapters",
    primaryKeys = ["source", "manga_id", "url"],
    indices = [Index(value = ["manga_id", "source"])]
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
    val source: String,
    /** Scroll position inside [last_page_read], in 1/10000 of the page height (webtoon modes). */
    @ColumnInfo(defaultValue = "0")
    val last_page_offset: Int = 0,
)

data class UpdateItem(
    @Embedded val chapter: Chapter,
    val webtoonTitle: String,
    val webtoonThumbnail: String?,
    val sourceId: String
)

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE manga_id = :mangaId AND source = :sourceId ORDER BY chapter_number DESC")
    fun getChaptersFlow(mangaId: String, sourceId: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters")
    suspend fun getAll(): List<Chapter>

    @Query("SELECT * FROM chapters")
    fun getAllChaptersFlow(): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE manga_id = :mangaId AND source = :sourceId ORDER BY chapter_number DESC")
    suspend fun getChapters(mangaId: String, sourceId: String): List<Chapter>

    @Query("SELECT * FROM chapters WHERE url = :url AND manga_id = :mangaId AND source = :sourceId")
    suspend fun getChapter(url: String, mangaId: String, sourceId: String): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<Chapter>)

    @Update
    suspend fun update(chapter: Chapter)

    @Transaction
    suspend fun updateAll(chapters: List<Chapter>) {
        chapters.forEach { update(it) }
    }

    @Query("UPDATE chapters SET read = :isRead WHERE url = :url AND manga_id = :mangaId AND source = :sourceId")
    suspend fun updateRead(url: String, mangaId: String, sourceId: String, isRead: Boolean)

    @Query("UPDATE chapters SET read = :isRead WHERE manga_id = :mangaId AND source = :sourceId AND url IN (:urls)")
    suspend fun updateRead(mangaId: String, sourceId: String, urls: List<String>, isRead: Boolean)

    @Query("UPDATE chapters SET bookmark = :bookmark WHERE manga_id = :mangaId AND source = :sourceId AND url IN (:urls)")
    suspend fun updateBookmark(mangaId: String, sourceId: String, urls: List<String>, bookmark: Boolean)

    @Query("UPDATE chapters SET last_page_read = :lastPage WHERE url = :url AND manga_id = :mangaId AND source = :sourceId")
    suspend fun updateLastPageRead(url: String, mangaId: String, sourceId: String, lastPage: Int)

    @Query("UPDATE chapters SET last_page_read = :lastPage, last_page_offset = :offset WHERE url = :url AND manga_id = :mangaId AND source = :sourceId")
    suspend fun updateReadPosition(url: String, mangaId: String, sourceId: String, lastPage: Int, offset: Int)

    @Query("DELETE FROM chapters WHERE manga_id = :mangaId AND source = :sourceId")
    suspend fun deleteByManga(mangaId: String, sourceId: String)

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
