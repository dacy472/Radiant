package com.radiant.webtoon.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as WebtoonApplication
        val repository = app.repository
        val extensionManager = app.extensionManager

        Log.d(TAG, "Starting Library Update Worker...")

        try {
            val favorites = repository.getLibrary()
            if (favorites.isEmpty()) {
                Log.d(TAG, "No favorites found. Exiting.")
                app.settingsManager.setLastLibraryUpdate(System.currentTimeMillis())
                return@withContext Result.success()
            }

            var updatedCount = 0

            for (manga in favorites) {
                val source = extensionManager.getSource(manga.source)
                if (source == null) {
                    Log.w(TAG, "Source ${manga.source} not found for ${manga.title}")
                    continue
                }

                try {
                    val remoteChapters = com.radiant.webtoon.core.concurrent.AppWorkQueues.withSourceNetwork {
                        source.getChapterList(manga.url)
                    }
                    val existingChapters = repository.getChaptersForManga(manga.url)
                    val existingUrls = existingChapters.map { it.url }.toSet()

                    val newChapters = remoteChapters
                        .filter { !existingUrls.contains(it.id) }
                        .map { remote ->
                            Chapter(
                                url = remote.id,
                                manga_id = manga.url,
                                name = remote.title,
                                date_fetch = System.currentTimeMillis(),
                                date_upload = 0L,
                                chapter_number = remote.number?.toFloat() ?: -1f,
                                source = manga.source,
                            )
                        }

                    if (newChapters.isNotEmpty()) {
                        Log.d(TAG, "Inserting ${newChapters.size} new chapters for ${manga.title}")
                        repository.insertChaptersList(newChapters)
                        updatedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get updates for ${manga.title}", e)
                }
            }

            if (updatedCount > 0) {
                showNotification(updatedCount)
            }

            app.settingsManager.setLastLibraryUpdate(System.currentTimeMillis())
            Log.d(TAG, "Library update complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Top-level worker failure", e)
            Result.retry()
        }
    }

    private fun showNotification(updatedCount: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("New chapters found")
            .setContentText("$updatedCount titles have updates")
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "library_update_worker"
        // Separate unique work name for manual, on-demand triggers so they never collide
        // with the periodic unique work (mixing one-time and periodic requests under the
        // same unique name is unsupported by WorkManager).
        const val MANUAL_WORK_NAME = "library_update_worker_manual"
        private const val TAG = "LibraryUpdateWorker"
        private const val CHANNEL_ID = "library_updates"
        private const val NOTIFICATION_ID = 4001

        private fun networkConstraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Triggers a one-off update, deduplicated against any already-running/pending manual
         * trigger (rapid repeated taps won't stack multiple concurrent worker runs).
         */
        fun triggerManualUpdate(context: Context) {
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
