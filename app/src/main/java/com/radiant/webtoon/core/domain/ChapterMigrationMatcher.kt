package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter

/**
 * Matches chapters between two webtoons by parsed floating-point chapter number.
 */
object ChapterMigrationMatcher {

    private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

    fun parseChapterNumber(chapter: Chapter): Float? {
        if (chapter.chapter_number >= 0f) return chapter.chapter_number
        return parseChapterNumberFromName(chapter.name)
    }

    fun parseChapterNumberFromName(name: String): Float? {
        val match = CHAPTER_NUMBER_REGEX.find(name) ?: return null
        return match.value.toFloatOrNull()
    }

    /**
     * Pairs old chapters with new chapters that share the same parsed chapter number.
     * When multiple chapters share a number, they are matched in list order.
     */
    fun matchChapters(
        oldChapters: List<Chapter>,
        newChapters: List<Chapter>,
    ): List<Pair<Chapter, Chapter>> {
        val oldByNumber = oldChapters
            .mapNotNull { chapter -> parseChapterNumber(chapter)?.let { it to chapter } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, chapters) -> chapters.toMutableList() }

        val matches = mutableListOf<Pair<Chapter, Chapter>>()
        for (newChapter in newChapters) {
            val number = parseChapterNumber(newChapter) ?: continue
            val oldQueue = oldByNumber[number] ?: continue
            if (oldQueue.isEmpty()) continue
            matches += oldQueue.removeAt(0) to newChapter
        }
        return matches
    }
}
