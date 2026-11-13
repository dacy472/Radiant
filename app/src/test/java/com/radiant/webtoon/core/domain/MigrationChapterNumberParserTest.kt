package com.radiant.webtoon.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationChapterNumberParserTest {

    @Test
    fun storedNumberWinsWhenKnown() {
        assertEquals(12.5f, ChapterNumberParser.parse(12.5f, "Chapter 99"))
        assertEquals(0f, ChapterNumberParser.parse(0f, "Prologue"))
    }

    @Test
    fun unknownStoredNumberFallsBackToName() {
        assertEquals(7f, ChapterNumberParser.parse(-1f, "Episode 7"))
        assertEquals(3f, ChapterNumberParser.parse(Float.NaN, "Ch. 3"))
        assertEquals(1f, ChapterNumberParser.parse(-0.5f, "Chapter 1"))
    }

    @Test
    fun knownRejectsNegativeNaNAndInfinite() {
        assertFalse(ChapterNumberParser.isKnown(-1f))
        assertFalse(ChapterNumberParser.isKnown(Float.NaN))
        assertFalse(ChapterNumberParser.isKnown(Float.POSITIVE_INFINITY))
        assertTrue(ChapterNumberParser.isKnown(0f))
        assertTrue(ChapterNumberParser.isKnown(12.5f))
    }

    @Test
    fun parsesDecimals() {
        assertEquals(12.5f, ChapterNumberParser.parseName("Chapter 12.5"))
        assertEquals(12.5f, ChapterNumberParser.parseName("12.5"))
        assertEquals(0.5f, ChapterNumberParser.parseName("Ch 0.5 - Side story"))
    }

    @Test
    fun parsesCommonPrefixes() {
        assertEquals(3f, ChapterNumberParser.parseName("Ch. 3"))
        assertEquals(3f, ChapterNumberParser.parseName("Ch3"))
        assertEquals(3f, ChapterNumberParser.parseName("chapter 3"))
        assertEquals(7f, ChapterNumberParser.parseName("Episode 7"))
        assertEquals(7f, ChapterNumberParser.parseName("Ep.7"))
        assertEquals(12f, ChapterNumberParser.parseName("#12"))
    }

    @Test
    fun parsesLeadingNumberWithTitle() {
        assertEquals(45f, ChapterNumberParser.parseName("45 - The Hunt Begins"))
        assertEquals(45f, ChapterNumberParser.parseName("45. The Hunt Begins"))
    }

    @Test
    fun keywordBeatsEarlierUnrelatedNumbers() {
        assertEquals(5f, ChapterNumberParser.parseName("Vol.2 Ch.5"))
        assertEquals(7f, ChapterNumberParser.parseName("Season 2 Episode 7"))
    }

    @Test
    fun returnsNullWhenNoNumber() {
        assertNull(ChapterNumberParser.parseName("Bonus"))
        assertNull(ChapterNumberParser.parseName("   "))
        assertNull(ChapterNumberParser.parseName(""))
        assertNull(ChapterNumberParser.parse(-1f, "Special"))
    }
}
