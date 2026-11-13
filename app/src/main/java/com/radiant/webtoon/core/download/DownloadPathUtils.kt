package com.radiant.webtoon.core.download

import java.io.File

object DownloadPathUtils {

    private val INVALID_CHARS = Regex("[/\\\\:*?\"<>|\\x00-\\x1f]")

    fun sanitizePathSegment(name: String): String {
        val cleaned = name.trim()
            .replace(INVALID_CHARS, "_")
            .replace(Regex("\\s+"), " ")
            .trim('.')
            .take(120)
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            throw IllegalArgumentException("Illegal path segment: $name")
        }
        if (cleaned.contains("..")) {
            throw IllegalArgumentException("Illegal path segment: $name")
        }
        return cleaned
    }

    fun identityDirName(readable: String?, id: String): String {
        val ident = sanitizePathSegment(
            id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank {
                Integer.toHexString(id.hashCode())
            },
        )
        val prefix = readable?.let { runCatching { sanitizePathSegment(it) }.getOrNull() }
        return if (prefix.isNullOrBlank() || prefix == ident) ident else "${prefix}__$ident"
    }

    fun pageFileName(index: Int): String = String.format("page_%03d.jpg", index + 1)

    fun chapterRelativePath(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        sourceName: String? = null,
        mangaTitle: String? = null,
        chapterTitle: String? = null,
    ): String {
        return listOf(
            identityDirName(sourceName, sourceId),
            identityDirName(mangaTitle, mangaId),
            identityDirName(chapterTitle, chapterId),
        ).joinToString(File.separator)
    }

    fun legacyRelativePath(sourceName: String, mangaTitle: String, chapterTitle: String): String {
        return listOf(
            sanitizePathSegment(sourceName),
            sanitizePathSegment(mangaTitle),
            sanitizePathSegment(chapterTitle),
        ).joinToString(File.separator)
    }

    fun defaultChapterDir(
        baseDir: File,
        sourceId: String,
        mangaId: String,
        chapterId: String,
        sourceName: String,
        mangaTitle: String,
        chapterTitle: String,
    ): File {
        val identity = File(baseDir, "downloads/${chapterRelativePath(sourceId, mangaId, chapterId, sourceName, mangaTitle, chapterTitle)}")
        val legacy = runCatching {
            File(baseDir, "downloads/${legacyRelativePath(sourceName, mangaTitle, chapterTitle)}")
        }.getOrNull()
        if (legacy != null && legacy.exists()) return legacy
        return identity
    }
}
