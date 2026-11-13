/*
 * Structure adapted from Mihon's `WebtoonLayoutManager` (https://github.com/mihonapp/mihon),
 * Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer.webtoon

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Plain vertical [LinearLayoutManager] that lays out an extra viewport-and-a-half on both sides,
 * so neighbouring pages are bound (and start loading) before they scroll into view and the user
 * rarely sees a placeholder. Item prefetch is disabled because page binding is expensive and the
 * extra layout space already covers it.
 */
class WebtoonLayoutManager(
    context: Context,
    private val extraLayoutSpacePx: Int = context.resources.displayMetrics.heightPixels * 3 / 2,
) : LinearLayoutManager(context) {

    init {
        isItemPrefetchEnabled = false
    }

    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        extraLayoutSpace[0] = extraLayoutSpacePx
        extraLayoutSpace[1] = extraLayoutSpacePx
    }

    /**
     * Last item whose end has been fully scrolled into view; falls back to the last partially
     * visible item when a single (tall) page is bigger than the viewport.
     */
    fun findLastEndVisibleItemPosition(): Int {
        val complete = findLastCompletelyVisibleItemPosition()
        return if (complete != RecyclerView.NO_POSITION) complete else findLastVisibleItemPosition()
    }
}
