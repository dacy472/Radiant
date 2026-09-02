package com.radiant.webtoon.core.usecase

import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.util.ChapterDates

/** Composite key for a manga row in the library (url + source). */
data class MangaIdentifier(
    val url: String,
    val sourceId: String,
)

/**
 * Migrates reading progress from a library entry on one source to a matching title on another.
 */
class MigrateWebtoonUseCase(
    private val repository: WebtoonRepository,
) {
    suspend operator fun invoke(
        oldId: MangaIdentifier,
        newId: MangaIdentifier,
        newWebtoon: Webtoon,
    ) {
        val oldManga = repository.mangaDao.getManga(oldId.url, oldId.sourceId)
            ?: throw IllegalStateException("Source manga not found in library")

        val newSource = repository.extensionManager.getSource(newId.sourceId)
            ?: throw IllegalStateException("Target source not found")

        val newChaptersRaw = newSource.getChapterList(newWebtoon.id)
        val oldChapters = repository.getChaptersForManga(oldManga.url)
        val readChapters = oldChapters.filter { it.read }

        val newChapters = newChaptersRaw.map { remote ->
            val matchingOld = readChapters.find {
                it.chapter_number == remote.number?.toFloat()
            }

            Chapter(
                url = remote.id,
                manga_id = newWebtoon.id,
                name = remote.title,
                chapter_number = remote.number?.toFloat() ?: -1f,
                source = newId.sourceId,
                read = matchingOld != null,
                bookmark = matchingOld?.bookmark ?: false,
                last_page_read = matchingOld?.last_page_read ?: 0,
                date_fetch = 0L,
                date_upload = ChapterDates.millisFrom(
                    rawDate = remote.date,
                    millis = remote.dateUploadMillis,
                ).takeIf { it > 0L } ?: matchingOld?.date_upload ?: 0L,
            )
        }

        repository.executeMigration(
            oldUrl = oldId.url,
            oldSource = oldId.sourceId,
            newUrl = newId.url,
            newSource = newId.sourceId,
            newThumbnail = newWebtoon.thumbnail,
            newChapters = newChapters,
        )
    }
}
