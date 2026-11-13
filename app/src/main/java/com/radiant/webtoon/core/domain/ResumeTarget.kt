package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter

/** Chapter the details screen's Start / Resume button opens. */
data class ResumeTarget(
    val chapter: Chapter,
    val kind: Kind,
) {
    enum class Kind { START, RESUME, READ_AGAIN }

    companion object {

        /**
         * Oldest → newest. Known chapter numbers sort ascending; chapters without a number keep
         * reversed source order (sources list newest first) and go after numbered ones.
         */
        fun readingOrder(chapters: List<Chapter>): List<Chapter> =
            chapters.asReversed().sortedBy { ch ->
                if (ch.chapter_number < 0f) Float.MAX_VALUE else ch.chapter_number
            }

        /**
         * - No progress anywhere → [Kind.START] at the first chapter.
         * - Last opened chapter (from history, else the newest chapter with progress) unfinished →
         *   [Kind.RESUME] there; the reader restores its saved page and offset.
         * - Otherwise the next unread chapter after it, then any unread chapter → [Kind.RESUME].
         * - Everything read → [Kind.READ_AGAIN] at the first chapter.
         */
        fun resolve(chapters: List<Chapter>, lastOpenedChapterUrl: String?): ResumeTarget? {
            if (chapters.isEmpty()) return null
            val ordered = readingOrder(chapters)

            val historyIndex = lastOpenedChapterUrl?.let { url -> ordered.indexOfFirst { it.url == url } } ?: -1
            val anchorIndex = if (historyIndex >= 0) {
                historyIndex
            } else {
                ordered.indexOfLast { it.read || it.last_page_read > 0 || it.last_page_offset > 0 }
            }
            if (anchorIndex < 0) return ResumeTarget(ordered.first(), Kind.START)

            val anchor = ordered[anchorIndex]
            if (!anchor.read) return ResumeTarget(anchor, Kind.RESUME)

            val nextUnread = ordered.subList(anchorIndex + 1, ordered.size).firstOrNull { !it.read }
                ?: ordered.firstOrNull { !it.read }
            return if (nextUnread != null) {
                ResumeTarget(nextUnread, Kind.RESUME)
            } else {
                ResumeTarget(ordered.first(), Kind.READ_AGAIN)
            }
        }
    }
}
