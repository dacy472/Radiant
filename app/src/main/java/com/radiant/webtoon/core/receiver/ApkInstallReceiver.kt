package com.radiant.webtoon.core.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log

/**
 * Handles DownloadManager completions for extension APKs only.
 * Tracks download IDs enqueued by this app so unrelated downloads are ignored.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (downloadId == -1L) return

        // Only act on downloads this app enqueued for extension installs
        if (!trackedDownloadIds.remove(downloadId)) {
            Log.d(TAG, "Ignoring unrelated download id=$downloadId")
            return
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = downloadManager.query(query) ?: return

        cursor.use {
            if (!it.moveToFirst()) return
            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val mimeIdx = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val localUriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            if (statusIdx < 0 || it.getInt(statusIdx) != DownloadManager.STATUS_SUCCESSFUL) return

            val mime = if (mimeIdx >= 0) it.getString(mimeIdx) else null
            val localUri = if (localUriIdx >= 0) it.getString(localUriIdx) else null
            val looksLikeApk = mime == "application/vnd.android.package-archive" ||
                localUri?.endsWith(".apk", ignoreCase = true) == true

            if (!looksLikeApk) {
                Log.d(TAG, "Ignoring non-APK download id=$downloadId mime=$mime")
                return
            }

            val uri = downloadManager.getUriForDownloadedFile(downloadId) ?: return
            Log.d(TAG, "Extension download complete, starting install intent for: $uri")
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(installIntent)
        }
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"
        private val trackedDownloadIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        fun trackDownload(downloadId: Long) {
            trackedDownloadIds.add(downloadId)
        }
    }
}
