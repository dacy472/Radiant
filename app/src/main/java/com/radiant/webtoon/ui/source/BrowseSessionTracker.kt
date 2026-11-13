package com.radiant.webtoon.ui.source

data class BrowseSessionKey(
    val sourceId: String,
    val tab: BrowseTab,
    val query: String,
    val filtersFingerprint: String,
    val generation: Long,
)

class BrowseSessionTracker {
    var current: BrowseSessionKey? = null
        private set

    fun reset(
        sourceId: String,
        tab: BrowseTab,
        query: String,
        filtersFingerprint: String,
    ): BrowseSessionKey {
        val next = BrowseSessionKey(
            sourceId = sourceId,
            tab = tab,
            query = query,
            filtersFingerprint = filtersFingerprint,
            generation = (current?.generation ?: 0L) + 1L,
        )
        current = next
        return next
    }

    fun isCurrent(key: BrowseSessionKey): Boolean = current == key
}
