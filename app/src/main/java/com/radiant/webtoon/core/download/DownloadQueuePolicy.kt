package com.radiant.webtoon.core.download

import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask

object DownloadQueuePolicy {
    fun availableSlots(runningJobs: Int, maxConcurrent: Int): Int =
        (maxConcurrent - runningJobs).coerceAtLeast(0)

    fun shouldKeepPolling(hasPendingOrDownloading: Boolean, runningJobs: Int): Boolean =
        hasPendingOrDownloading || runningJobs > 0

    fun recoveredStatus(task: DownloadTask, hasInMemoryJob: Boolean): DownloadStatus {
        if (task.status == DownloadStatus.DOWNLOADING && !hasInMemoryJob) {
            return DownloadStatus.PENDING
        }
        return task.status
    }
}
