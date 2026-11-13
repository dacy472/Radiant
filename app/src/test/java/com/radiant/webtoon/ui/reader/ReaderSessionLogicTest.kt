package com.radiant.webtoon.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionLogicTest {
    @Test
    fun delayedAIsIgnoredAfterB() {
        assertFalse(ReaderSessionLogic.shouldAccept(activeGeneration = 2, incomingGeneration = 1))
        assertTrue(ReaderSessionLogic.shouldAccept(activeGeneration = 2, incomingGeneration = 2))
    }

    @Test
    fun onePageChapterDoesNotMarkReadWhileLoading() {
        assertFalse(
            ReaderSessionLogic.shouldMarkRead(
                isLoading = true,
                pagesReady = false,
                currentPageOneBased = 1,
                pageCount = 1,
                activeGeneration = 2,
                eventGeneration = 2,
            ),
        )
    }

    @Test
    fun resumeClampsWhenPageCountShrinks() {
        assertEquals(2, ReaderSessionLogic.resumePageIndex(savedPage = 9, pageCount = 3))
        assertEquals(0, ReaderSessionLogic.resumePageIndex(savedPage = 0, pageCount = 12))
        assertEquals(0, ReaderSessionLogic.resumePageIndex(savedPage = 4, pageCount = 0))
    }

    @Test
    fun offsetOnlyAppliesToTheSavedPage() {
        assertEquals(4200, ReaderSessionLogic.resumePageOffset(requestedPage = 5, resumePage = 5, savedOffset = 4200))
        assertEquals(0, ReaderSessionLogic.resumePageOffset(requestedPage = 9, resumePage = 2, savedOffset = 4200))
        assertEquals(9_999, ReaderSessionLogic.resumePageOffset(requestedPage = 1, resumePage = 1, savedOffset = 20_000))
    }

    @Test
    fun newChapterStartsAtZeroWhenNoSavedPage() {
        assertEquals(0, ReaderSessionLogic.resumePageIndex(savedPage = 0, pageCount = 20))
    }
}
