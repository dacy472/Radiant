package com.radiant.webtoon.core.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background task that checks for extension updates every 12 hours.
 * Requires network connectivity; defers silently when offline.
 */
class ExtensionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as WebtoonApplication
        try {
            Log.d(TAG, "Starting extension update check")
            app.extensionManager.runBackgroundExtensionUpdateCheck()
            Log.d(TAG, "Extension update check complete")
        } catch (e: Exception) {
            // Fail silently — no user-facing error for background network checks
            Log.w(TAG, "Extension update check failed (ignored): ${e.message}")
        }
        Result.success()
    }

    companion object {
        const val WORK_NAME = "extension_update_check"
        private const val TAG = "ExtensionUpdateWorker"
        private const val CHECK_INTERVAL_HOURS = 12L

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /** Schedule (or keep) the 12-hour periodic update check. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(
                CHECK_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "Scheduled extension update checks every ${CHECK_INTERVAL_HOURS}h")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
