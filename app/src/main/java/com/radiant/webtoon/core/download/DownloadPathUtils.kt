package com.radiant.webtoon.core.download

import java.io.File

object DownloadPathUtils {

    private val INVALID_CHARS = Regex("[/\\\\:*?\"<>|\\x00-\\x1f]")

    fun sanitizePathSegment(name: String): String {
        return name.trim()
            .replace(INVALID_CHARS, "_")
            .replace(Regex("\\s+"), " ")
            .take(120)
            .ifBlank { "unknown" }
    }

    fun pageFileName(index: Int): String = String.format("page_%03d.jpg", index + 1)

    /**
     * Relative path under the downloads root:
     * `{sourceName}/{mangaTitle}/{chapterTitle}/`
     */
    fun chapterRelativePath(sourceName: String, mangaTitle: String, chapterTitle: String): String {
        return listOf(
            sanitizePathSegment(sourceName),
            sanitizePathSegment(mangaTitle),
            sanitizePathSegment(chapterTitle),
        ).joinToString(File.separator)
    }

    fun defaultChapterDir(baseDir: File, sourceName: String, mangaTitle: String, chapterTitle: String): File {
        return File(baseDir, "downloads/${chapterRelativePath(sourceName, mangaTitle, chapterTitle)}")
    }
}
