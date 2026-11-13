package com.radiant.webtoon.core.domain.usecase

import androidx.room.withTransaction
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.History
import com.radiant.webtoon.core.database.LibraryCategoryCrossRef
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.Track
import com.radiant.webtoon.core.database.WebtoonDatabase

/**
 * Persistence port used by [MigrateWebtoonUseCase]. The production implementation
 * ([RoomMigrationStore]) wraps the existing DAOs; tests substitute an in-memory fake so the
 * migration rules can be verified on the JVM.
 */
interface MigrationStore {
    /** Runs [block] atomically: if it throws, every write made inside it must be rolled back. */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    suspend fun getManga(url: String, sourceId: String): Manga?
    suspend fun getChapters(mangaUrl: String, sourceId: String): List<Chapter>
    suspend fun updateChapters(chapters: List<Chapter>)

    suspend fun getHistory(mangaUrl: String, sourceId: String): List<History>
    suspend fun insertHistory(entries: List<History>)

    suspend fun getCategoryIds(mangaUrl: String, sourceId: String): List<Int>
    suspend fun replaceCategories(mangaUrl: String, sourceId: String, categoryIds: List<Int>)

    suspend fun getTracks(mangaUrl: String, sourceId: String): List<Track>
    suspend fun insertTracks(tracks: List<Track>)

    suspend fun setFavorite(mangaUrl: String, sourceId: String, favorite: Boolean, dateAdded: Long)
}

class RoomMigrationStore(private val database: WebtoonDatabase) : MigrationStore {
    private val mangaDao get() = database.mangaDao()
    private val chapterDao get() = database.chapterDao()
    private val historyDao get() = database.historyDao()
    private val categoryDao get() = database.categoryDao()
    private val trackDao get() = database.trackDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    override suspend fun getManga(url: String, sourceId: String) = mangaDao.getManga(url, sourceId)

    override suspend fun getChapters(mangaUrl: String, sourceId: String) =
        chapterDao.getChapters(mangaUrl, sourceId)

    override suspend fun updateChapters(chapters: List<Chapter>) = chapterDao.updateAll(chapters)

    override suspend fun getHistory(mangaUrl: String, sourceId: String) =
        historyDao.getForWebtoon(mangaUrl, sourceId)

    override suspend fun insertHistory(entries: List<History>) {
        entries.forEach { historyDao.insert(it) }
    }

    override suspend fun getCategoryIds(mangaUrl: String, sourceId: String): List<Int> =
        categoryDao.getMappingsForWebtoon(mangaUrl, sourceId).map { it.categoryId }

    override suspend fun replaceCategories(mangaUrl: String, sourceId: String, categoryIds: List<Int>) {
        categoryDao.removeMappingsForWebtoon(mangaUrl, sourceId)
        categoryIds.distinct().forEach { id ->
            categoryDao.addMapping(LibraryCategoryCrossRef(mangaUrl, sourceId, id))
        }
    }

    override suspend fun getTracks(mangaUrl: String, sourceId: String) =
        trackDao.getForManga(mangaUrl, sourceId)

    override suspend fun insertTracks(tracks: List<Track>) {
        tracks.forEach { trackDao.insert(it) }
    }

    override suspend fun setFavorite(mangaUrl: String, sourceId: String, favorite: Boolean, dateAdded: Long) =
        mangaDao.updateFavorite(mangaUrl, sourceId, favorite, dateAdded)
}
