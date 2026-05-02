package com.radiant.webtoon.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext as WebtoonApplication
        val repository = app.repository
        val extensionManager = app.extensionManager

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("updates", "Library Updates", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = 2001
        
        fun updateNotification(progress: Int, max: Int, title: String) {
            val notification = NotificationCompat.Builder(context, "updates")
                .setContentTitle("Updating Library")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setProgress(max, progress, false)
                .setOngoing(true)
                .build()
            notificationManager.notify(notificationId, notification)
        }

        try {
            val libraryItems = repository.getLibrary()
            val total = libraryItems.size
            if (total == 0) return@withContext Result.success()

            var updatedCount = 0

            libraryItems.forEachIndexed { index, webtoon ->
                updateNotification(index, total, webtoon.title)
                
                try {
                    val source = extensionManager.getSource(webtoon.source)
                    if (source != null) {
                        // Fetch latest from source
                        val detailedWebtoon = source.getWebtoonDetail(webtoon.url)
                        
                        // Get current locally cached chapters
                        val localChapters = repository.getChaptersForManga(webtoon.url)
                        val localChapterIds = localChapters.map { it.url }.toSet()

                        val newChaptersToInsert = mutableListOf<Chapter>()
                        var isFirstChapterSync = localChapters.isEmpty()
                        
                        detailedWebtoon.chapters.forEach { chapter ->
                            if (!localChapterIds.contains(chapter.id)) {
                                newChaptersToInsert.add(
                                    Chapter(
                                        url = chapter.id,
                                        source = webtoon.source,
                                        manga_id = webtoon.url,
                                        name = chapter.title,
                                        chapter_number = chapter.number?.toFloat() ?: -1f,
                                        date_upload = chapter.date?.toLongOrNull() ?: System.currentTimeMillis(),
                                        read = false,
                                        date_fetch = System.currentTimeMillis()
                                    )
                                )
                            }
                        }

                        if (newChaptersToInsert.isNotEmpty()) {
                            if(isFirstChapterSync) {
                                val adjustedChapters = newChaptersToInsert.map { it.copy(date_fetch = 0L) }
                                repository.insertChaptersList(adjustedChapters)
                            } else {
                                repository.insertChaptersList(newChaptersToInsert)
                                updatedCount += newChaptersToInsert.size
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue even if one fails
                }
            }

            notificationManager.cancel(notificationId)
            
            if (updatedCount > 0) {
                val notification = NotificationCompat.Builder(context, "updates")
                    .setContentTitle("Update Complete")
                    .setContentText("Found $updatedCount new chapters")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setAutoCancel(true)
                    .build()
                notificationManager.notify(notificationId + 1, notification)
            }

            Result.success()
        } catch (e: Exception) {
            notificationManager.cancel(notificationId)
            Result.retry()
        }
    }
}
