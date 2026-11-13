package com.radiant.webtoon.core.domain

import com.radiant.webtoon.core.database.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationChapterMatchingTest {

    private fun ch(
        url: String,
        number: Float,
        name: String = "Chapter $number",
        scanlator: String? = null,
        manga: String = "m",
        source: String = "s",
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPage: Int = 0,
        dateFetch: Long = 0L,
    ) = Chapter(
        url = url,
        manga_id = manga,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        last_page_read = lastPage,
        date_fetch = dateFetch,
        chapter_number = number,
        source = source,
    )

    private fun pairs(result: List<Pair<Chapter, Chapter>>) = result.map { it.first.url to it.second.url }

    @Test
    fun emptyListsYieldNoMatches() {
        assertTrue(ChapterMigrationMatcher.matchChapters(emptyList(), emptyList()).isEmpty())
        assertTrue(ChapterMigrationMatcher.matchChapters(listOf(ch("o", 1f)), emptyList()).isEmpty())
        assertTrue(ChapterMigrationMatcher.matchChapters(emptyList(), listOf(ch("n", 1f))).isEmpty())
    }

    @Test
    fun exactMatchByNumber() {
        val old = listOf(ch("o1", 1f), ch("o2", 2f))
        val new = listOf(ch("n2", 2f), ch("n1", 1f), ch("n3", 3f))
        assertEquals(listOf("o1" to "n1", "o2" to "n2"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun decimalChaptersMatch() {
        val old = listOf(ch("o", 12.5f))
        val new = listOf(ch("n12", 12f), ch("n125", 12.5f), ch("n13", 13f))
        assertEquals(listOf("o" to "n125"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun numbersWithinEpsilonAreEqual() {
        val old = listOf(ch("o", 3.00001f))
        val new = listOf(ch("n", 3.0f))
        assertEquals(1, ChapterMigrationMatcher.matchChapters(old, new).size)
    }

    @Test
    fun numbersBeyondEpsilonAreDifferent() {
        val old = listOf(ch("o", 3.001f))
        val new = listOf(ch("n", 3.0f))
        assertTrue(ChapterMigrationMatcher.matchChapters(old, new).isEmpty())
    }

    @Test
    fun duplicateNewNumbersMatchedOnceEach() {
        val old = listOf(ch("o", 1f))
        val new = listOf(ch("n-a", 1f), ch("n-b", 1f))
        // Only one old chapter, so only one new chapter may be paired (the first).
        assertEquals(listOf("o" to "n-a"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun duplicateOldNumbersMatchedAtMostOnce() {
        val old = listOf(ch("o-a", 1f), ch("o-b", 1f))
        val new = listOf(ch("n", 1f))
        assertEquals(listOf("o-a" to "n"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun duplicatesPreferSameScanlator() {
        val old = listOf(ch("o", 1f, name = "x", scanlator = "Group B"))
        val new = listOf(
            ch("n-a", 1f, name = "y", scanlator = "Group A"),
            ch("n-b", 1f, name = "z", scanlator = "group b"),
        )
        assertEquals(listOf("o" to "n-b"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun duplicatesPreferSameTitleWhenNoScanlatorMatch() {
        val old = listOf(ch("o", 1f, name = "Chapter 1: Part Two"))
        val new = listOf(
            ch("n-a", 1f, name = "Chapter 1: Part One"),
            ch("n-b", 1f, name = "chapter 1 - part two"),
        )
        // "Chapter 1: Part Two" vs "chapter 1 - part two" normalise to the same string.
        assertEquals(listOf("o" to "n-b"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun unmatchedAndUnknownNumbersAreLeftOut() {
        val old = listOf(ch("o1", 1f), ch("o-extra", 99f), ch("o-bonus", -1f, name = "Bonus"))
        val new = listOf(ch("n1", 1f), ch("n2", 2f))
        val result = ChapterMigrationMatcher.matchChapters(old, new)
        assertEquals(listOf("o1" to "n1"), pairs(result))
    }

    @Test
    fun unknownStoredNumberFallsBackToNameParsing() {
        val old = listOf(ch("o", -1f, name = "45 - Title"))
        val new = listOf(ch("n", -1f, name = "Chapter 45"))
        assertEquals(listOf("o" to "n"), pairs(ChapterMigrationMatcher.matchChapters(old, new)))
    }

    @Test
    fun progressTransferCopiesStateAndKeepsNewIdentity() {
        val old = ch(
            "old-url", 5f, name = "Old five", manga = "old-manga", source = "old-src",
            read = true, bookmark = true, lastPage = 17, dateFetch = 1234L,
        )
        val new = ch("new-url", 5f, name = "New five", scanlator = "NG", manga = "new-manga", source = "new-src")

        val out = ChapterProgressTransfer.apply(old, new)

        assertTrue(out.read)
        assertTrue(out.bookmark)
        assertEquals(17, out.last_page_read)
        assertEquals(1234L, out.date_fetch)
        // identity retained from the new chapter
        assertEquals("new-url", out.url)
        assertEquals("new-manga", out.manga_id)
        assertEquals("new-src", out.source)
        assertEquals("New five", out.name)
        assertEquals("NG", out.scanlator)
        assertEquals(5f, out.chapter_number)
    }

    @Test
    fun progressTransferOverwritesWithOldUnreadState() {
        val old = ch("o", 1f, read = false, bookmark = false, lastPage = 0, dateFetch = 0L)
        val new = ch("n", 1f, read = true, bookmark = true, lastPage = 9, dateFetch = 55L)
        val out = ChapterProgressTransfer.apply(old, new)
        assertFalse(out.read)
        assertFalse(out.bookmark)
        assertEquals(0, out.last_page_read)
        assertEquals(0L, out.date_fetch)
    }
}
