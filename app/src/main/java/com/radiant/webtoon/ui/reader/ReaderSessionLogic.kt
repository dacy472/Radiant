package com.radiant.webtoon.ui.reader

object ReaderSessionLogic {
    fun shouldAccept(activeGeneration: Long, incomingGeneration: Long): Boolean =
        activeGeneration == incomingGeneration

    fun resumePageIndex(savedPage: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return savedPage.coerceIn(0, pageCount - 1)
    }

    /** The saved in-page offset only applies when the saved page itself is reopened (not clamped). */
    fun resumePageOffset(requestedPage: Int, resumePage: Int, savedOffset: Int): Int =
        if (requestedPage == resumePage) savedOffset.coerceIn(0, 9_999) else 0

    fun shouldMarkRead(
        isLoading: Boolean,
        pagesReady: Boolean,
        currentPageOneBased: Int,
        pageCount: Int,
        activeGeneration: Long,
        eventGeneration: Long,
    ): Boolean {
        if (isLoading || !pagesReady || pageCount <= 0) return false
        if (activeGeneration != eventGeneration) return false
        val threshold = (pageCount * 0.9f).toInt().coerceAtLeast(1)
        return currentPageOneBased >= threshold || currentPageOneBased >= pageCount
    }
}
