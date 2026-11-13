package com.radiant.webtoon.core.local

import androidx.room.withTransaction
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.database.DownloadedChapterDao
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.MangaDao
import com.radiant.webtoon.core.database.WebtoonDatabase

class LocalLibrarySync(
    private val database: WebtoonDatabase,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val downloadedChapterDao: DownloadedChapterDao,
) {
    suspend fun sync(scan: ScanResult) {
        val sourceId = LocalSourceIds.LOCAL_SOURCE_ID
        val now = System.currentTimeMillis()
        val scannedSeriesIds = scan.series.map { it.id }.toSet()
        val scannedChapterIds = scan.series.flatMap { series ->
            series.chapters.map { it.id }
        }.toSet()

        database.withTransaction {
            val existingManga = mangaDao.getAllBySource(sourceId)
            existingManga.filter { it.url !in scannedSeriesIds }.forEach { stale ->
                chapterDao.deleteByManga(stale.url, sourceId)
                downloadedChapterDao.deleteByWebtoon(sourceId, stale.url)
                mangaDao.delete(stale.url, sourceId)
            }

            val existingDownloads = downloadedChapterDao.getAllBySource(sourceId)
            existingDownloads.filter { it.chapterId !in scannedChapterIds }.forEach { stale ->
                downloadedChapterDao.delete(sourceId, stale.chapterId)
            }

            for (series in scan.series) {
                val existing = mangaDao.getManga(series.id, sourceId)
                mangaDao.upsertFetchedMetadata(
                    Manga(
                        url = series.id,
                        source = sourceId,
                        title = series.title,
                        thumbnail_url = series.coverPath,
                        status = "Local",
                        favorite = true,
                        initialized = true,
                        date_added = existing?.date_added?.takeIf { it > 0 } ?: now,
                        last_update = now,
                    ),
                )

                val existingChapters = chapterDao.getChapters(series.id, sourceId).associateBy { it.url }
                chapterDao.insertAll(
                    series.chapters.map { ch ->
                        val prior = existingChapters[ch.id]
                        Chapter(
                            url = ch.id,
                            manga_id = series.id,
                            name = ch.title,
                            read = prior?.read ?: false,
                            bookmark = prior?.bookmark ?: false,
                            last_page_read = prior?.last_page_read ?: 0,
                            last_page_offset = prior?.last_page_offset ?: 0,
                            date_fetch = prior?.date_fetch ?: 0L,
                            date_upload = prior?.date_upload ?: 0L,
                            chapter_number = ch.number?.toFloat() ?: -1f,
                            source = sourceId,
                        )
                    },
                )

                downloadedChapterDao.insertAll(
                    series.chapters.map { ch ->
                        DownloadedChapter(
                            sourceId = sourceId,
                            chapterId = ch.id,
                            webtoonId = series.id,
                            webtoonTitle = series.title,
                            chapterTitle = ch.title,
                            sourceName = LocalSourceIds.LOCAL_SOURCE_NAME,
                            localDirectory = ch.localPath,
                            pageCount = ch.pageCount,
                            fileSizeBytes = ch.fileSizeBytes,
                        )
                    },
                )
            }
        }
    }
}
