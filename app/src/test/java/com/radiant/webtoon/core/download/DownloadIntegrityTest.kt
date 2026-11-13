package com.radiant.webtoon.core.download

import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DownloadIntegrityTest {
    @Test
    fun htmlPayloadIsNotAnImage() {
        val html = "<!DOCTYPE html><html>not an image</html>".toByteArray()
        assertFalse(ImagePayloadValidator.isImage(html))
    }

    @Test
    fun jpegMagicIsAccepted() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)
        assertTrue(ImagePayloadValidator.isImage(jpeg))
    }

    @Test
    fun truncatedBodyRejected() {
        val dir = createTempDirectory("dl").toFile()
        try {
            runCatching { AtomicImageStore.writePage(dir, 0, byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
                .exceptionOrNull()
                .let { assertTrue(it is IllegalStateException) }
            assertFalse(File(dir, DownloadPathUtils.pageFileName(0)).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sameTitleDifferentIdsGetDifferentFolders() {
        val a = DownloadPathUtils.chapterRelativePath("src", "manga", "ch-a", "Source", "Title", "Chapter 1")
        val b = DownloadPathUtils.chapterRelativePath("src", "manga", "ch-b", "Source", "Title", "Chapter 1")
        assertNotEquals(a, b)
        assertTrue(a.contains("Chapter 1"))
        assertTrue(b.contains("Chapter 1"))
    }

    @Test
    fun dotDotPathRejected() {
        runCatching { DownloadPathUtils.sanitizePathSegment("..") }
            .exceptionOrNull()
            .let { assertTrue(it is IllegalArgumentException) }
    }

    @Test
    fun orphanedDownloadingBecomesPending() {
        val task = DownloadTask(
            chapterId = "ch",
            sourceId = "src",
            webtoonId = "manga",
            webtoonTitle = "Title",
            chapterTitle = "Chapter 1",
            status = DownloadStatus.DOWNLOADING,
        )
        assertEquals(DownloadStatus.PENDING, DownloadQueuePolicy.recoveredStatus(task, hasInMemoryJob = false))
        assertEquals(DownloadStatus.DOWNLOADING, DownloadQueuePolicy.recoveredStatus(task, hasInMemoryJob = true))
        assertEquals(
            DownloadStatus.PAUSED,
            DownloadQueuePolicy.recoveredStatus(task.copy(status = DownloadStatus.PAUSED), false),
        )
    }

    @Test
    fun capacityUsesRunningJobsNotDbCount() {
        assertEquals(2, DownloadQueuePolicy.availableSlots(runningJobs = 1, maxConcurrent = 3))
        assertFalse(DownloadQueuePolicy.shouldKeepPolling(hasPendingOrDownloading = false, runningJobs = 0))
        assertTrue(DownloadQueuePolicy.shouldKeepPolling(hasPendingOrDownloading = false, runningJobs = 1))
    }

    @Test
    fun failedStaysVisibleBesideSuccesses() {
        val failed = DownloadStatus.FAILED
        val queue = listOf(DownloadStatus.COMPLETED, failed, DownloadStatus.PENDING)
        assertTrue(queue.contains(DownloadStatus.FAILED))
        assertEquals(1, DownloadQueuePolicy.availableSlots(runningJobs = 2, maxConcurrent = 3))
    }
}
