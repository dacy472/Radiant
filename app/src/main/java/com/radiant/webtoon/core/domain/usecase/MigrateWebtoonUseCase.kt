package com.radiant.webtoon.core.domain.usecase

import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.domain.ChapterMigrationMatcher
import com.radiant.webtoon.core.domain.ChapterProgressTransfer

/** Composite identity of a library row: `Manga.url` + `Manga.source` (Room primary key). */
data class MangaIdentifier(
    val url: String,
    val sourceId: String,
)

/** What to carry over from the old entry. Defaults mirror Mihon's migration dialog. */
data class MigrationOptions(
    val copyChapterProgress: Boolean = true,
    val copyHistory: Boolean = true,
    val copyCategories: Boolean = true,
    val copyTracking: Boolean = true,
    val copyDateAdded: Boolean = true,
    /** Un-favorites the old entry and drops its category mappings (rows/chapters are kept). */
    val removeOldFromLibrary: Boolean = true,
)

data class MigrationResult(
    val matchedCount: Int,
    val totalOld: Int,
    val totalNew: Int,
    val unmatchedOld: Int,
)

/**
 * Moves reading progress, bookmarks, history, categories, tracking and library membership from
 * [oldId] to [newId] in one atomic transaction.
 *
 * Both entries must already exist in Room and the new entry's chapters must already be loaded
 * (see `WebtoonRepository.loadWebtoonForMigration`) — nothing here touches the network.
 * Any exception thrown inside the transaction rolls every write back and is rethrown.
 */
class MigrateWebtoonUseCase(private val store: MigrationStore) {

    constructor(database: WebtoonDatabase) : this(RoomMigrationStore(database))

    suspend operator fun invoke(
        oldId: MangaIdentifier,
        newId: MangaIdentifier,
        options: MigrationOptions = MigrationOptions(),
    ): MigrationResult {
        require(oldId != newId) { "Cannot migrate a webtoon onto itself" }

        return store.inTransaction {
            val oldManga = store.getManga(oldId.url, oldId.sourceId)
                ?: error("Old webtoon not found: ${oldId.url} (${oldId.sourceId})")
            val newManga = store.getManga(newId.url, newId.sourceId)
                ?: error("New webtoon not found: ${newId.url} (${newId.sourceId})")

            val oldChapters = store.getChapters(oldId.url, oldId.sourceId)
            val newChapters = store.getChapters(newId.url, newId.sourceId)
            val matches = ChapterMigrationMatcher.matchChapters(oldChapters, newChapters)

            if (options.copyChapterProgress && matches.isNotEmpty()) {
                store.updateChapters(matches.map { (old, new) -> ChapterProgressTransfer.apply(old, new) })
            }

            if (options.copyHistory && matches.isNotEmpty()) {
                val oldHistory = store.getHistory(oldId.url, oldId.sourceId).associateBy { it.chapter_id }
                val copied = matches.mapNotNull { (old, new) ->
                    oldHistory[old.url]?.copy(
                        chapter_id = new.url,
                        webtoon_id = newId.url,
                        source_id = newId.sourceId,
                        chapter_title = new.name,
                        webtoon_title = newManga.title,
                        webtoon_thumbnail = newManga.thumbnail_url,
                    )
                }
                if (copied.isNotEmpty()) store.insertHistory(copied)
            }

            if (options.copyCategories) {
                // Replace (not merge) so the new entry ends up in exactly the old entry's categories.
                store.replaceCategories(
                    newId.url,
                    newId.sourceId,
                    store.getCategoryIds(oldId.url, oldId.sourceId).distinct(),
                )
            }

            if (options.copyTracking) {
                val tracks = store.getTracks(oldId.url, oldId.sourceId)
                    .map { it.copy(mangaUrl = newId.url, mangaSource = newId.sourceId) }
                if (tracks.isNotEmpty()) store.insertTracks(tracks)
            }

            val dateAdded = when {
                options.copyDateAdded && oldManga.date_added > 0L -> oldManga.date_added
                newManga.date_added > 0L -> newManga.date_added
                else -> System.currentTimeMillis()
            }
            store.setFavorite(newId.url, newId.sourceId, favorite = true, dateAdded = dateAdded)

            if (options.removeOldFromLibrary) {
                store.setFavorite(oldId.url, oldId.sourceId, favorite = false, dateAdded = 0L)
                store.replaceCategories(oldId.url, oldId.sourceId, emptyList())
            }

            MigrationResult(
                matchedCount = matches.size,
                totalOld = oldChapters.size,
                totalNew = newChapters.size,
                unmatchedOld = oldChapters.size - matches.size,
            )
        }
    }
}
