package com.radiant.webtoon.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * WorkManager-backed CoroutineWorker that downloads all pages of a chapter and
 * packages them into a CBZ (ZIP) archive on external storage.
 *
 * Input data keys:
 *   KEY_SOURCE_ID   — the source ID string
 *   KEY_WEBTOON_ID  — the manga URL (primary key in Manga table)
 *   KEY_CHAPTER_ID  — the chapter URL (primary key in chapters table)
 *   KEY_WEBTOON_TITLE  — webtoon display title (for directory naming)
 *   KEY_CHAPTER_TITLE  — chapter display title  (for file naming)
 */
class ChapterDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SOURCE_ID      = "sourceId"
        const val KEY_WEBTOON_ID     = "webtoonId"
        const val KEY_CHAPTER_ID     = "chapterId"
        const val KEY_WEBTOON_TITLE  = "webtoonTitle"
        const val KEY_CHAPTER_TITLE  = "chapterTitle"

        private const val NOTIFICATION_CHANNEL_ID = "chapter_download"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "ChapterDownloadWorker"
    }

    private val networkHelper: NetworkHelper by injectLazy()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading chapter…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceId     = inputData.getString(KEY_SOURCE_ID)     ?: return@withContext Result.failure()
        val webtoonId    = inputData.getString(KEY_WEBTOON_ID)    ?: return@withContext Result.failure()
        val chapterId    = inputData.getString(KEY_CHAPTER_ID)    ?: return@withContext Result.failure()
        val webtoonTitle = inputData.getString(KEY_WEBTOON_TITLE) ?: "Unknown"
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: chapterId

        val app        = applicationContext as WebtoonApplication
        val repository = app.repository
        val downloadDao = app.database.downloadDao()

        // ── 1. Upsert a DownloadTask row so the UI can observe progress ──
        val existingTask = downloadDao.getByChapter(chapterId, sourceId)
        val task = existingTask?.copy(
            status = DownloadStatus.DOWNLOADING,
            progress = 0
        ) ?: DownloadTask(
            chapterId    = chapterId,
            sourceId     = sourceId,
            webtoonId    = webtoonId,
            webtoonTitle = webtoonTitle,
            chapterTitle = chapterTitle,
            status       = DownloadStatus.DOWNLOADING
        )
        downloadDao.insert(task) // REPLACE on conflict

        try {
            // ── 2. Resolve the source ──
            val source = app.extensionManager.getSource(sourceId)
                ?: run {
                    Log.e(TAG, "Source '$sourceId' not found")
                    downloadDao.insert(task.copy(status = DownloadStatus.FAILED))
                    return@withContext Result.failure()
                }

            // ── 3. Fetch page URLs from the source ──
            val pageUrls = repository.getChapterPages(sourceId, chapterId)
            if (pageUrls.isEmpty()) {
                Log.w(TAG, "No pages returned for chapter $chapterId")
                downloadDao.insert(task.copy(status = DownloadStatus.FAILED))
                return@withContext Result.failure()
            }

            val total = pageUrls.size
            downloadDao.insert(task.copy(totalPages = total, status = DownloadStatus.DOWNLOADING))

            // ── 4. Build the CBZ output path ──
            val safeTitle   = webtoonTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val safeChapter = chapterTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val downloadDir = File(
                applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "${source.name}/$safeTitle"
            )
            downloadDir.mkdirs()
            val cbzFile = File(downloadDir, "$safeChapter.cbz")

            // ── 5. Stream pages into a ZipOutputStream ──
            val client = networkHelper.client
            var archivedPages = 0
            ZipOutputStream(cbzFile.outputStream().buffered()).use { zos ->
                pageUrls.forEachIndexed { index, pageUrl ->
                    val entryName = String.format("%03d.jpg", index + 1)
                    try {
                        val request = Request.Builder()
                            .url(pageUrl)
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.w(TAG, "Page ${index + 1} HTTP ${response.code} — skipping")
                                return@forEachIndexed
                            }
                            val bytes = response.body?.bytes() ?: return@forEachIndexed

                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(bytes)
                            zos.closeEntry()
                            archivedPages++

                            Log.d(TAG, "Archived page ${index + 1}/$total → $entryName")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to archive page ${index + 1}: ${e.message}")
                    }

                    // Update progress after each page
                    val progress = ((index + 1).toFloat() / total * 100).toInt()
                    downloadDao.insert(
                        task.copy(
                            status = DownloadStatus.DOWNLOADING,
                            totalPages = total,
                            downloadedPages = archivedPages,
                            progress = progress
                        )
                    )
                    showProgressNotification(chapterTitle, index + 1, total)
                }
            }

            if (archivedPages == 0) {
                cbzFile.delete()
                downloadDao.insert(task.copy(status = DownloadStatus.FAILED))
                Log.e(TAG, "No pages archived for chapter $chapterId")
                return@withContext Result.failure()
            }

            // ── 6. Mark complete in the DB ──
            downloadDao.insert(
                task.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    totalPages = total,
                    downloadedPages = archivedPages,
                    localPath = cbzFile.absolutePath
                )
            )
            showCompletionNotification(webtoonTitle, chapterTitle)
            Log.i(TAG, "Download complete → ${cbzFile.absolutePath} ($archivedPages/$total pages)")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for chapter $chapterId", e)
            downloadDao.insert(task.copy(status = DownloadStatus.FAILED))
            Result.failure()
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Chapter Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Progress for chapter CBZ downloads" }
                )
            }
        }
    }

    private fun showProgressNotification(chapterTitle: String, done: Int, total: Int) {
        createNotificationChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading $chapterTitle")
            .setContentText("Page $done of $total")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(webtoonTitle: String, chapterTitle: String) {
        createNotificationChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText("$webtoonTitle – $chapterTitle")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notification)
    }
}
