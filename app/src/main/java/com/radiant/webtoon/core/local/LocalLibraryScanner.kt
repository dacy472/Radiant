package com.radiant.webtoon.core.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File

class LocalLibraryScanner(
    private val context: Context,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val TAG = "LocalLibraryScanner"
        private const val SINGLE_CHAPTER_SUFFIX = "__single__"

        private val COVER_NAMES = setOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "folder.jpg", "folder.jpeg")

        fun parseChapterNumber(name: String): Double? {
            val match = Regex("""(\d+(?:\.\d+)?)""").find(name) ?: return null
            return match.value.toDoubleOrNull()
        }

        fun isPageFileName(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
        }

        fun isArchiveName(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase()
            return lower.endsWith(".cbz") || lower.endsWith(".zip")
        }

        fun defaultRootDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Webtoons")
    }

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        try {
            val safUri = settingsManager.localLibraryUri.firstOrNull()
            if (!safUri.isNullOrBlank()) {
                return@withContext scanSaf(Uri.parse(safUri))
            }

            val customPath = settingsManager.localLibraryPath.firstOrNull()
            val root = if (!customPath.isNullOrBlank()) {
                File(customPath)
            } else {
                defaultRootDir()
            }

            if (!root.exists()) {
                root.mkdirs()
            }
            if (!root.isDirectory) {
                return@withContext ScanResult(emptyList(), root.absolutePath, "Path is not a directory")
            }

            ScanResult(LocalFileScanner.scanFileRoot(root), root.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Local library scan failed", e)
            ScanResult(emptyList(), "", e.message)
        }
    }

    private fun scanSaf(rootUri: Uri): ScanResult {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: return ScanResult(emptyList(), rootUri.toString(), "Cannot open SAF folder")
        if (!root.isDirectory) {
            return ScanResult(emptyList(), rootUri.toString(), "SAF URI is not a directory")
        }

        val series = root.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            when {
                child.isFile && isArchiveName(name) ->
                    parseSingleChapterArchiveSaf(child, name, name.substringBeforeLast('.'))
                child.isDirectory -> parseSeriesDirectorySaf(child, name)
                else -> null
            }
        }.sortedBy { it.title.lowercase() }

        return ScanResult(series, rootUri.toString())
    }

    private fun parseSeriesDirectorySaf(dir: DocumentFile, seriesId: String): LocalSeries? {
        val children = dir.listFiles()
        val chapterEntries = children.filter { child ->
            val name = child.name
            (child.isDirectory && hasPageFilesSaf(child)) ||
                (child.isFile && isArchiveName(name))
        }

        val chapters = when {
            chapterEntries.isNotEmpty() -> chapterEntries.mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                when {
                    child.isDirectory -> chapterFromDirectorySaf(child, seriesId, name)
                    child.isFile -> chapterFromArchiveSaf(child, seriesId, name.substringBeforeLast('.'))
                    else -> null
                }
            }
            hasPageFilesSaf(dir) -> listOf(chapterFromDirectorySaf(dir, seriesId, seriesId, singleInSeries = true))
            else -> emptyList()
        }

        if (chapters.isEmpty()) return null

        return LocalSeries(
            id = seriesId,
            title = seriesId,
            coverPath = findCoverPathSaf(dir, chapters),
            chapters = chapters.sortedWith(compareBy({ it.number ?: Double.MAX_VALUE }, { it.title })),
        )
    }

    private fun parseSingleChapterArchiveSaf(file: DocumentFile, seriesId: String, title: String): LocalSeries {
        val chapter = chapterFromArchiveSaf(file, seriesId, title, singleInSeries = true)
        return LocalSeries(seriesId, title, coverPath = null, chapters = listOf(chapter))
    }

    private fun chapterFromDirectorySaf(
        dir: DocumentFile,
        seriesId: String,
        title: String,
        singleInSeries: Boolean = false,
    ): LocalChapter {
        val chapterKey = if (singleInSeries) SINGLE_CHAPTER_SUFFIX else title
        val files = dir.listFiles()
        return LocalChapter(
            id = "$seriesId/$chapterKey",
            title = if (singleInSeries) title else title,
            number = if (singleInSeries) 1.0 else parseChapterNumber(title),
            localPath = dir.uri.toString(),
            pageCount = files.count { isPageFileName(it.name) },
            fileSizeBytes = files.filter { isPageFileName(it.name) }.sumOf { it.length() },
        )
    }

    private fun chapterFromArchiveSaf(
        file: DocumentFile,
        seriesId: String,
        title: String,
        singleInSeries: Boolean = false,
    ): LocalChapter {
        val chapterKey = if (singleInSeries) SINGLE_CHAPTER_SUFFIX else title
        return LocalChapter(
            id = "$seriesId/$chapterKey",
            title = if (singleInSeries) title else title,
            number = if (singleInSeries) 1.0 else parseChapterNumber(title),
            localPath = file.uri.toString(),
            pageCount = 0,
            fileSizeBytes = file.length(),
        )
    }

    private fun findCoverPathSaf(seriesDir: DocumentFile, chapters: List<LocalChapter>): String? {
        seriesDir.listFiles().firstOrNull { file ->
            file.isFile && COVER_NAMES.contains(file.name?.lowercase())
        }?.let { return it.uri.toString() }

        seriesDir.listFiles().firstOrNull { file ->
            file.isFile && isPageFileName(file.name)
        }?.let { return it.uri.toString() }

        val firstChapter = chapters.firstOrNull() ?: return null
        if (!firstChapter.localPath.startsWith("content://")) return null
        val chapterDir = DocumentFile.fromTreeUri(context, Uri.parse(firstChapter.localPath))
        chapterDir?.listFiles()?.firstOrNull { isPageFileName(it.name) }?.let {
            return it.uri.toString()
        }
        return null
    }

    private fun hasPageFilesSaf(dir: DocumentFile): Boolean =
        dir.listFiles().any { it.isFile && isPageFileName(it.name) }
}
