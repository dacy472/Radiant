package com.radiant.webtoon.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import java.util.concurrent.TimeUnit

class LibraryUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WebtoonApplication
        val repository = app.repository
        return runCatching {
            val library = repository.getLibrary()
            var updatedCount = 0
            library.forEach { item ->
                val detail = repository.getWebtoonDetail(item.source, item.url)
                if (detail.chapters.isNotEmpty()) {
                    // Check if the latest chapter is known locally
                    val chapters = repository.getChaptersForManga(item.url)
                    if (chapters.isEmpty() || detail.chapters.first().url != chapters.first().url) {
                         updatedCount += 1
                    }
                }
            }
            if (updatedCount > 0) {
                showNotification(updatedCount)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    private fun showNotification(updatedCount: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library updates", NotificationManager.IMPORTANCE_DEFAULT)
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
        private const val WORK_NAME = "library_update_worker"
        private const val CHANNEL_ID = "library_updates"
        private const val NOTIFICATION_ID = 4001

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
