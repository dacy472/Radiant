package com.radiant.webtoon.core.domain.usecase

import androidx.room.withTransaction
import com.radiant.webtoon.core.database.CategoryDao
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.database.LibraryCategoryCrossRef
import com.radiant.webtoon.core.database.MangaDao
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.domain.ChapterMigrationMatcher

data class MigrateWebtoonResult(
    val matchedChapterCount: Int,
    val totalOldChapters: Int,
    val totalNewChapters: Int,
)

/**
 * Migrates reading progress, bookmarks, library favorite status, and category
 * assignments from one webtoon entry to another.
 *
 * Both webtoons must already exist in Room with chapters loaded. Manga primary key
 * is `(url, source)` where [oldWebtoonId]/[newWebtoonId] map to `Manga.url`.
 */
class MigrateWebtoonUseCase(
    private val database: WebtoonDatabase,
    private val chapterDao: ChapterDao,
    private val mangaDao: MangaDao,
    private val categoryDao: CategoryDao,
) {
    constructor(database: WebtoonDatabase) : this(
        database = database,
        chapterDao = database.chapterDao(),
        mangaDao = database.mangaDao(),
        categoryDao = database.categoryDao(),
    )

    suspend operator fun invoke(
        oldWebtoonId: String,
        oldSourceId: String,
        newWebtoonId: String,
        newSourceId: String,
    ): MigrateWebtoonResult {
        var result = MigrateWebtoonResult(0, 0, 0)
        database.withTransaction {
            val oldManga = mangaDao.getManga(oldWebtoonId, oldSourceId)
                ?: error("Old webtoon not found: $oldWebtoonId ($oldSourceId)")
            mangaDao.getManga(newWebtoonId, newSourceId)
                ?: error("New webtoon not found: $newWebtoonId ($newSourceId)")

            val oldChapters = chapterDao.getChapters(oldWebtoonId)
            val newChapters = chapterDao.getChapters(newWebtoonId)
            val matches = ChapterMigrationMatcher.matchChapters(oldChapters, newChapters)

            val chapterUpdates = matches.map { (oldChapter, newChapter) ->
                newChapter.copy(
                    read = oldChapter.read,
                    bookmark = oldChapter.bookmark,
                    last_page_read = oldChapter.last_page_read,
                )
            }
            if (chapterUpdates.isNotEmpty()) {
                chapterDao.updateAll(chapterUpdates)
            }

            copyCategoryAssignments(
                oldWebtoonId = oldWebtoonId,
                oldSourceId = oldSourceId,
                newWebtoonId = newWebtoonId,
                newSourceId = newSourceId,
            )

            val dateAdded = oldManga.date_added.takeIf { it > 0L } ?: System.currentTimeMillis()
            mangaDao.updateFavorite(newWebtoonId, newSourceId, isFavorite = true, date = dateAdded)
            mangaDao.updateFavorite(oldWebtoonId, oldSourceId, isFavorite = false, date = 0)

            result = MigrateWebtoonResult(
                matchedChapterCount = matches.size,
                totalOldChapters = oldChapters.size,
                totalNewChapters = newChapters.size,
            )
        }
        return result
    }

    private suspend fun copyCategoryAssignments(
        oldWebtoonId: String,
        oldSourceId: String,
        newWebtoonId: String,
        newSourceId: String,
    ) {
        val oldMappings = categoryDao.getMappingsForWebtoon(oldWebtoonId, oldSourceId)
        if (oldMappings.isEmpty()) return

        categoryDao.removeMappingsForWebtoon(newWebtoonId, newSourceId)
        oldMappings.forEach { mapping ->
            categoryDao.addMapping(
                LibraryCategoryCrossRef(
                    webtoonId = newWebtoonId,
                    sourceId = newSourceId,
                    categoryId = mapping.categoryId,
                ),
            )
        }
    }
}
