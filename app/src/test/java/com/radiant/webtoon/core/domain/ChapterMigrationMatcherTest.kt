package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMigrationMatcherTest {

    private fun chapter(
        url: String,
        mangaId: String,
        name: String,
        number: Float = -1f,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPage: Int = 0,
    ) = Chapter(
        url = url,
        manga_id = mangaId,
        name = name,
        chapter_number = number,
        read = read,
        bookmark = bookmark,
        last_page_read = lastPage,
        source = "test",
    )

    @Test
    fun parseChapterNumber_prefersStoredField() {
        val ch = chapter("1", "manga", "Ignored title", number = 12.5f)
        assertEquals(12.5f, ChapterMigrationMatcher.parseChapterNumber(ch))
    }

    @Test
    fun parseChapterNumber_fallsBackToName() {
        val ch = chapter("1", "manga", "Chapter 42.5")
        assertEquals(42.5f, ChapterMigrationMatcher.parseChapterNumber(ch))
    }

    @Test
    fun matchChapters_pairsByChapterNumber() {
        val oldChapters = listOf(
            chapter("old-1", "old", "Ch 1", number = 1f, read = true, lastPage = 3),
            chapter("old-2", "old", "Ch 2", number = 2f, bookmark = true),
            chapter("old-3", "old", "Bonus", number = -1f),
        )
        val newChapters = listOf(
            chapter("new-1", "new", "Episode 1", number = 1f),
            chapter("new-2", "new", "Episode 2", number = 2f),
            chapter("new-3", "new", "Episode 3", number = 3f),
        )

        val matches = ChapterMigrationMatcher.matchChapters(oldChapters, newChapters)

        assertEquals(2, matches.size)
        assertEquals("old-1" to "new-1", matches[0].let { it.first.url to it.second.url })
        assertEquals("old-2" to "new-2", matches[1].let { it.first.url to it.second.url })
        assertTrue(matches[0].first.read)
        assertEquals(3, matches[0].first.last_page_read)
        assertTrue(matches[1].first.bookmark)
    }

    @Test
    fun matchChapters_handlesDuplicateNumbersInOrder() {
        val oldChapters = listOf(
            chapter("old-a", "old", "1", number = 1f, read = true),
            chapter("old-b", "old", "1 alt", number = 1f, read = false),
        )
        val newChapters = listOf(
            chapter("new-a", "new", "1", number = 1f),
            chapter("new-b", "new", "1 alt", number = 1f),
        )

        val matches = ChapterMigrationMatcher.matchChapters(oldChapters, newChapters)

        assertEquals(2, matches.size)
        assertEquals("old-a", matches[0].first.url)
        assertEquals("old-b", matches[1].first.url)
    }
}
