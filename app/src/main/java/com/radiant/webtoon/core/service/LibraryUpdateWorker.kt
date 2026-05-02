package com.radiant.webtoon.core.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as WebtoonApplication
        val repository = app.repository
        val extensionManager = app.extensionManager

        Log.d("LibraryUpdateWorker", "Starting Library Update Worker...")

        try {
            val favorites = repository.getLibrary()
            if (favorites.isEmpty()) {
                Log.d("LibraryUpdateWorker", "No favorites found. Exiting.")
                return@withContext Result.success()
            }

            for (manga in favorites) {
                val source = extensionManager.getSource(manga.source)
                if (source == null) {
                    Log.w("LibraryUpdateWorker", "Source ${manga.source} not found for ${manga.title}")
                    continue
                }

                try {
                    // source.getChapterList returns a list of com.radiant.webtoon.core.model.Chapter
                    val remoteChapters = source.getChapterList(manga.url)
                    val existingChapters = repository.getChaptersForManga(manga.url)
                    val existingUrls = existingChapters.map { it.url }.toSet()

                    val newChaptersToInsert = mutableListOf<com.radiant.webtoon.core.database.Chapter>()

                    for (remote in remoteChapters) {
                        // If it's a completely new chapter we haven't tracked yet
                        if (!existingUrls.contains(remote.id)) {
                            newChaptersToInsert.add(
                                com.radiant.webtoon.core.database.Chapter(
                                    url = remote.id,
                                    manga_id = manga.url,
                                    name = remote.title,
                                    date_fetch = System.currentTimeMillis(),
                                    date_upload = 0L,
                                    chapter_number = 1f,
                                    source = manga.source
                                )
                            )
                        }
                    }

                    if (newChaptersToInsert.isNotEmpty()) {
                        Log.d("LibraryUpdateWorker", "Inserting ${newChaptersToInsert.size} new chapters for ${manga.title}")
                        repository.insertChaptersList(newChaptersToInsert)
                    }

                } catch (e: Exception) {
                    Log.e("LibraryUpdateWorker", "Failed to get updates for ${manga.title}", e)
                }
            }

            Log.d("LibraryUpdateWorker", "Library update complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e("LibraryUpdateWorker", "Top-level worker failure", e)
            Result.retry()
        }
    }
}
