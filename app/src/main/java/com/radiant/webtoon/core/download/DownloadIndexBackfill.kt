package com.radiant.webtoon.core.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.database.DownloadedChapterDao
import com.radiant.webtoon.core.database.DownloadDao
import com.radiant.webtoon.core.database.MangaDao
import com.radiant.webtoon.core.database.ChapterDao
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-time migration that populates [DownloadedChapter] from existing DB rows and on-disk files.
 */
object DownloadIndexBackfill {
    private const val TAG = "DownloadIndexBackfill"

    suspend fun runIfNeeded(
        context: Context,
        downloadedChapterDao: DownloadedChapterDao,
        downloadDao: DownloadDao,
        mangaDao: MangaDao,
        chapterDao: ChapterDao,
        settingsManager: SettingsManager,
    ) = withContext(Dispatchers.IO) {
        if (settingsManager.isDownloadIndexBackfilled.firstOrNull() == true) return@withContext
        if (downloadedChapterDao.count() > 0) {
            settingsManager.setDownloadIndexBackfilled(true)
            return@withContext
        }

        Log.i(TAG, "Starting download index backfill")
        val entries = mutableListOf<DownloadedChapter>()

        downloadDao.getCompleted().forEach { task ->
            val path = task.localPath ?: return@forEach
            if (!pathExists(context, path)) return@forEach
            entries += DownloadedChapter(
                sourceId = task.sourceId,
                chapterId = task.chapterId,
                webtoonId = task.webtoonId,
                webtoonTitle = task.webtoonTitle,
                chapterTitle = task.chapterTitle,
                sourceName = task.sourceId,
                localDirectory = path,
                pageCount = task.totalPages.coerceAtLeast(task.downloadedPages),
                downloadedAt = task.dateAdded,
                fileSizeBytes = computeSize(context, path),
            )
        }

        scanDefaultDownloadsDir(context, mangaDao, chapterDao, entries)
        scanSafDownloads(context, settingsManager, mangaDao, chapterDao, entries)

        if (entries.isNotEmpty()) {
            downloadedChapterDao.insertAll(entries.distinctBy { it.sourceId to it.chapterId })
            Log.i(TAG, "Backfilled ${entries.size} downloaded chapter index row(s)")
        }
        settingsManager.setDownloadIndexBackfilled(true)
    }

    private suspend fun scanDefaultDownloadsDir(
        context: Context,
        mangaDao: MangaDao,
        chapterDao: ChapterDao,
        entries: MutableList<DownloadedChapter>,
    ) {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val downloadsRoot = File(baseDir, "downloads")
        if (!downloadsRoot.isDirectory) return

        downloadsRoot.walkTopDown()
            .maxDepth(4)
            .filter { it.isDirectory && hasPageFiles(it) }
            .forEach { chapterDir ->
                val relative = chapterDir.relativeTo(downloadsRoot).path
                val segments = relative.split(File.separatorChar, '/')
                if (segments.size < 3) return@forEach
                val sourceName = segments[0]
                val mangaTitle = segments[1]
                val chapterTitle = segments[2]
                val match = findChapterMatch(mangaDao, chapterDao, sourceName, mangaTitle, chapterTitle)
                    ?: return@forEach
                val key = match.sourceId to match.chapterId
                if (entries.any { it.sourceId == key.first && it.chapterId == key.second }) return@forEach
                entries += DownloadedChapter(
                    sourceId = match.sourceId,
                    chapterId = match.chapterId,
                    webtoonId = match.webtoonId,
                    webtoonTitle = match.webtoonTitle,
                    chapterTitle = match.chapterTitle,
                    sourceName = sourceName,
                    localDirectory = chapterDir.absolutePath,
                    pageCount = countPageFiles(chapterDir),
                    fileSizeBytes = computeSize(context, chapterDir.absolutePath),
                )
            }

        val legacyRoot = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (legacyRoot != null && legacyRoot.isDirectory) {
            legacyRoot.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && (it.extension.equals("cbz", true) || it.extension.equals("zip", true)) }
                .forEach { cbz ->
                    val relative = cbz.parentFile?.relativeTo(legacyRoot)?.path ?: return@forEach
                    val segments = relative.split(File.separatorChar, '/')
                    if (segments.size < 2) return@forEach
                    val sourceName = segments[0]
                    val mangaTitle = segments[1]
                    val chapterTitle = cbz.nameWithoutExtension
                    val match = findChapterMatch(mangaDao, chapterDao, sourceName, mangaTitle, chapterTitle)
                        ?: return@forEach
                    val key = match.sourceId to match.chapterId
                    if (entries.any { it.sourceId == key.first && it.chapterId == key.second }) return@forEach
                    entries += DownloadedChapter(
                        sourceId = match.sourceId,
                        chapterId = match.chapterId,
                        webtoonId = match.webtoonId,
                        webtoonTitle = match.webtoonTitle,
                        chapterTitle = match.chapterTitle,
                        sourceName = sourceName,
                        localDirectory = cbz.absolutePath,
                        pageCount = 0,
                        fileSizeBytes = cbz.length(),
                    )
                }
        }
    }

    private suspend fun scanSafDownloads(
        context: Context,
        settingsManager: SettingsManager,
        mangaDao: MangaDao,
        chapterDao: ChapterDao,
        entries: MutableList<DownloadedChapter>,
    ) {
        val uriStr = settingsManager.downloadLocationUri.firstOrNull() ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return
        scanSafDirectory(context, root, emptyList(), mangaDao, chapterDao, entries)
    }

    private suspend fun scanSafDirectory(
        context: Context,
        dir: DocumentFile,
        pathSegments: List<String>,
        mangaDao: MangaDao,
        chapterDao: ChapterDao,
        entries: MutableList<DownloadedChapter>,
    ) {
        val children = dir.listFiles()
        if (pathSegments.size >= 3 && children.any { isPageFileName(it.name) }) {
            val sourceName = pathSegments[0]
            val mangaTitle = pathSegments[1]
            val chapterTitle = pathSegments[2]
            val match = findChapterMatch(mangaDao, chapterDao, sourceName, mangaTitle, chapterTitle)
                ?: return
            val key = match.sourceId to match.chapterId
            if (entries.none { it.sourceId == key.first && it.chapterId == key.second }) {
                entries += DownloadedChapter(
                    sourceId = match.sourceId,
                    chapterId = match.chapterId,
                    webtoonId = match.webtoonId,
                    webtoonTitle = match.webtoonTitle,
                    chapterTitle = match.chapterTitle,
                    sourceName = sourceName,
                    localDirectory = dir.uri.toString(),
                    pageCount = children.count { isPageFileName(it.name) },
                    fileSizeBytes = children.filter { isPageFileName(it.name) }.sumOf { it.length() },
                )
            }
            return
        }
        for (child in children) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            scanSafDirectory(context, child, pathSegments + name, mangaDao, chapterDao, entries)
        }
    }

    private data class ChapterMatch(
        val sourceId: String,
        val webtoonId: String,
        val chapterId: String,
        val webtoonTitle: String,
        val chapterTitle: String,
    )

    private suspend fun findChapterMatch(
        mangaDao: MangaDao,
        chapterDao: ChapterDao,
        sourceName: String,
        mangaTitle: String,
        chapterTitle: String,
    ): ChapterMatch? {
        val normalizedManga = mangaTitle.trim().lowercase()
        val normalizedChapter = chapterTitle.trim().lowercase()
        val library = mangaDao.getFavorites()
        val manga = library.firstOrNull { it.title.trim().equals(mangaTitle, ignoreCase = true) }
            ?: library.firstOrNull { it.title.trim().lowercase() == normalizedManga }
            ?: return null
        val chapters = chapterDao.getChapters(manga.url)
        val chapter = chapters.firstOrNull { it.name.trim().equals(chapterTitle, ignoreCase = true) }
            ?: chapters.firstOrNull { it.name.trim().lowercase() == normalizedChapter }
            ?: return null
        return ChapterMatch(
            sourceId = manga.source,
            webtoonId = manga.url,
            chapterId = chapter.url,
            webtoonTitle = manga.title,
            chapterTitle = chapter.name,
        )
    }

    private fun hasPageFiles(dir: File): Boolean = dir.listFiles()?.any { isPageFileName(it.name) } == true

    private fun countPageFiles(dir: File): Int =
        dir.listFiles()?.count { isPageFileName(it.name) } ?: 0

    private fun isPageFileName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun pathExists(context: Context, path: String): Boolean {
        return if (path.startsWith("content://")) {
            DocumentFile.fromTreeUri(context, Uri.parse(path))?.exists() == true
        } else {
            File(path).exists()
        }
    }

    private fun computeSize(context: Context, path: String): Long {
        return if (path.startsWith("content://")) {
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(path)) ?: return 0L
            doc.listFiles().filter { isPageFileName(it.name) }.sumOf { it.length() }
        } else {
            val file = File(path)
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                file.isFile -> file.length()
                else -> 0L
            }
        }
    }
}
