package com.radiant.webtoon.core.download

import com.radiant.webtoon.core.database.DownloadTask
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of actively running chapter download jobs.
 */
class DownloadQueue {

    private val runningJobs = ConcurrentHashMap<String, Job>()

    fun taskKey(task: DownloadTask): String = "${task.sourceId}::${task.chapterId}"

    fun register(task: DownloadTask, job: Job) {
        runningJobs[taskKey(task)] = job
    }

    fun unregister(task: DownloadTask) {
        runningJobs.remove(taskKey(task))
    }

    fun getJob(task: DownloadTask): Job? = runningJobs[taskKey(task)]

    fun cancelJob(task: DownloadTask) {
        runningJobs.remove(taskKey(task))?.cancel()
    }

    fun activeCount(): Int = runningJobs.count { it.value.isActive }

    fun clear() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }
}
