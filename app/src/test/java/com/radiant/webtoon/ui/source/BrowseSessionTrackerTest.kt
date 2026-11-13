package com.radiant.webtoon.ui.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSessionTrackerTest {
    @Test
    fun searchAThenBIgnoresA() {
        val tracker = BrowseSessionTracker()
        val a = tracker.reset("src", BrowseTab.SEARCH, "one", "")
        val b = tracker.reset("src", BrowseTab.SEARCH, "two", "")
        assertFalse(tracker.isCurrent(a))
        assertTrue(tracker.isCurrent(b))
    }

    @Test
    fun popularToLatestMidRequestDropsStale() {
        val tracker = BrowseSessionTracker()
        val popular = tracker.reset("src", BrowseTab.POPULAR, "", "")
        val latest = tracker.reset("src", BrowseTab.LATEST, "", "")
        assertFalse(tracker.isCurrent(popular))
        assertTrue(tracker.isCurrent(latest))
    }

    @Test
    fun duplicateLoadMoreKeepsSameGeneration() {
        val tracker = BrowseSessionTracker()
        val session = tracker.reset("src", BrowseTab.POPULAR, "", "")
        assertTrue(tracker.isCurrent(session))
        assertTrue(tracker.isCurrent(session.copy()))
    }
}
