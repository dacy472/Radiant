package com.radiant.webtoon.core.local

import java.io.File
import java.util.zip.ZipFile

/**
 * Pure filesystem scanner — no Android Context required (unit-testable on JVM).
 */
object LocalFileScanner {
    private const val SINGLE_CHAPTER_SUFFIX = "__single__"

    private val COVER_NAMES = setOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "folder.jpg", "folder.jpeg")

    fun scanFileRoot(root: File): List<LocalSeries> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.mapNotNull { entry ->
                when {
                    entry.isFile && LocalLibraryScanner.isArchiveName(entry.name) ->
                        parseSingleChapterArchive(entry, entry.nameWithoutExtension, entry.nameWithoutExtension)
                    entry.isDirectory -> parseSeriesDirectory(entry, entry.name)
                    else -> null
                }
            }
            .orEmpty()
            .sortedBy { it.title.lowercase() }
    }

    private fun parseSeriesDirectory(dir: File, seriesId: String): LocalSeries? {
        val children = dir.listFiles().orEmpty()
        val chapterEntries = children.filter { child ->
            (child.isDirectory && hasPageFiles(child)) ||
                (child.isFile && LocalLibraryScanner.isArchiveName(child.name))
        }

        val chapters = when {
            chapterEntries.isNotEmpty() -> chapterEntries.mapNotNull { child ->
                when {
                    child.isDirectory -> chapterFromDirectory(child, seriesId, child.name)
                    child.isFile -> chapterFromArchive(child, seriesId, child.nameWithoutExtension)
                    else -> null
                }
            }
            hasPageFiles(dir) -> listOf(chapterFromDirectory(dir, seriesId, dir.name, singleInSeries = true))
            else -> emptyList()
        }

        if (chapters.isEmpty()) return null

        return LocalSeries(
            id = seriesId,
            title = dir.name,
            coverPath = findCoverPath(dir, chapters),
            chapters = chapters.sortedWith(compareBy({ it.number ?: Double.MAX_VALUE }, { it.title })),
        )
    }

    private fun parseSingleChapterArchive(file: File, seriesId: String, title: String): LocalSeries {
        val chapter = chapterFromArchive(file, seriesId, title, singleInSeries = true)
        return LocalSeries(
            id = seriesId,
            title = title,
            coverPath = null,
            chapters = listOf(chapter),
        )
    }

    private fun chapterFromDirectory(
        dir: File,
        seriesId: String,
        title: String,
        singleInSeries: Boolean = false,
    ): LocalChapter {
        val chapterKey = if (singleInSeries) SINGLE_CHAPTER_SUFFIX else dir.name
        return LocalChapter(
            id = "$seriesId/$chapterKey",
            title = if (singleInSeries) title else dir.name,
            number = if (singleInSeries) 1.0 else LocalLibraryScanner.parseChapterNumber(dir.name),
            localPath = dir.absolutePath,
            pageCount = countPageFiles(dir),
            fileSizeBytes = dirSize(dir),
        )
    }

    private fun chapterFromArchive(
        file: File,
        seriesId: String,
        title: String,
        singleInSeries: Boolean = false,
    ): LocalChapter {
        val chapterKey = if (singleInSeries) SINGLE_CHAPTER_SUFFIX else file.nameWithoutExtension
        return LocalChapter(
            id = "$seriesId/$chapterKey",
            title = if (singleInSeries) title else file.nameWithoutExtension,
            number = if (singleInSeries) 1.0 else LocalLibraryScanner.parseChapterNumber(file.nameWithoutExtension),
            localPath = file.absolutePath,
            pageCount = countArchivePages(file),
            fileSizeBytes = file.length(),
        )
    }

    private fun findCoverPath(seriesDir: File, chapters: List<LocalChapter>): String? {
        seriesDir.listFiles()?.firstOrNull { file ->
            file.isFile && COVER_NAMES.contains(file.name.lowercase())
        }?.let { return fileUri(it) }

        seriesDir.listFiles()?.firstOrNull { file ->
            file.isFile && LocalLibraryScanner.isPageFileName(file.name)
        }?.let { return fileUri(it) }

        val firstChapterPath = chapters.firstOrNull()?.localPath ?: return null
        val first = File(firstChapterPath)
        if (first.isDirectory) {
            first.listFiles()?.firstOrNull { LocalLibraryScanner.isPageFileName(it.name) }?.let { return fileUri(it) }
        }
        return null
    }

    private fun fileUri(file: File): String = "file://${file.absolutePath}"

    private fun hasPageFiles(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && LocalLibraryScanner.isPageFileName(it.name) } == true

    private fun countPageFiles(dir: File): Int =
        dir.listFiles()?.count { it.isFile && LocalLibraryScanner.isPageFileName(it.name) } ?: 0

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun countArchivePages(file: File): Int = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().count { entry ->
                !entry.isDirectory && LocalLibraryScanner.isPageFileName(entry.name.substringAfterLast('/'))
            }
        }
    }.getOrDefault(0)
}
