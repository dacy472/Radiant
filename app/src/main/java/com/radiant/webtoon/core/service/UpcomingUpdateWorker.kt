package com.radiant.webtoon.core.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.UpcomingRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpcomingUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as WebtoonApplication
            val dao = app.database.upcomingReleaseDao()
            val library = app.database.mangaDao().getFavorites()
            val releases = mutableListOf<UpcomingRelease>()
            val now = System.currentTimeMillis()
            val weekAhead = now + 7L * 24 * 60 * 60 * 1000

            for (manga in library) {
                val chapters = app.database.chapterDao().getChapters(manga.url, manga.source)
                val unread = chapters.filter { !it.read }
                val next = unread.minByOrNull { it.chapter_number } ?: continue
                val releaseAt = next.date_upload.takeIf { it > 0 } ?: (now + 24 * 60 * 60 * 1000)
                if (releaseAt in now..weekAhead) {
                    releases.add(
                        UpcomingRelease(
                            mangaUrl = manga.url,
                            mangaTitle = manga.title,
                            sourceId = manga.source,
                            chapterName = next.name,
                            releaseAt = releaseAt,
                        ),
                    )
                }
            }

            dao.clearAll()
            if (releases.isNotEmpty()) {
                dao.insertAll(releases.distinctBy { "${it.mangaUrl}:${it.chapterName}" })
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "upcoming_update"
    }
}
