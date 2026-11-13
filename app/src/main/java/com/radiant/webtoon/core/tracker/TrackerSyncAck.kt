package com.radiant.webtoon.core.tracker

object TrackerSyncAck {
    fun nextLastChapter(localLastChapter: Double, syncedChapter: Int, syncSucceeded: Boolean): Double {
        if (!syncSucceeded) return localLastChapter
        if (syncedChapter < localLastChapter.toInt()) return localLastChapter
        return maxOf(localLastChapter, syncedChapter.toDouble())
    }
}
