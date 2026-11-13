/*
 * Structure adapted from Mihon's `WebtoonAdapter` / `WebtoonPageHolder` /
 * `WebtoonTransitionHolder` (https://github.com/mihonapp/mihon), Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer.webtoon

import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.radiant.webtoon.ui.reader.model.ChapterTransition
import com.radiant.webtoon.ui.reader.model.ReaderPage
import com.radiant.webtoon.ui.reader.viewer.ReaderPageView
import com.radiant.webtoon.ui.reader.viewer.ReaderTransitionView

/** Items are [ReaderPage] or [ChapterTransition], in display order. */
class WebtoonAdapter(private val viewer: WebtoonViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<Any> = emptyList()
        private set

    fun setItems(newItems: List<Any>) {
        val old = items
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = sameItem(old[oldPos], newItems[newPos])
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == newItems[newPos]
            },
        )
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    private fun sameItem(a: Any, b: Any): Boolean = when {
        a is ReaderPage && b is ReaderPage -> a == b
        a is ChapterTransition.Prev && b is ChapterTransition.Prev -> a.from.id == b.from.id
        a is ChapterTransition.Next && b is ChapterTransition.Next -> a.from.id == b.from.id
        else -> false
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ReaderPage -> VIEW_TYPE_PAGE
        else -> VIEW_TYPE_TRANSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_PAGE -> WebtoonPageHolder(
                ReaderPageView(parent.context, viewer.host, isWebtoon = true),
                viewer,
            )
            else -> WebtoonTransitionHolder(ReaderTransitionView(parent.context), viewer)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is WebtoonPageHolder -> holder.bind(items[position] as ReaderPage)
            is WebtoonTransitionHolder -> holder.bind(items[position] as ChapterTransition)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is WebtoonPageHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    private companion object {
        const val VIEW_TYPE_PAGE = 0
        const val VIEW_TYPE_TRANSITION = 1
    }
}

/**
 * A page: `match_parent × wrap_content` plus an optional bottom margin (only the continuous
 * vertical mode uses it). All height comes from [ReaderPageView]: a viewport-tall placeholder while
 * loading, the exact fit-to-width height afterwards.
 */
class WebtoonPageHolder(
    val view: ReaderPageView,
    private val viewer: WebtoonViewer,
) : RecyclerView.ViewHolder(view) {

    init {
        view.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = viewer.pageSpacingPx
        }
    }

    fun bind(page: ReaderPage) {
        view.placeholderHeightPx = viewer.placeholderHeightPx()
        view.onFinalHeightLaidOut = { viewer.onPageHeightFinal(page) }
        view.bind(page)
    }

    fun recycle() {
        view.recycle()
    }
}

class WebtoonTransitionHolder(
    private val view: ReaderTransitionView,
    private val viewer: WebtoonViewer,
) : RecyclerView.ViewHolder(view) {

    init {
        view.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = viewer.pageSpacingPx
        }
    }

    fun bind(transition: ChapterTransition) {
        view.bind(transition, viewer.host.viewerSettings.contentColor) {
            when (transition) {
                is ChapterTransition.Next -> viewer.host.loadNextChapter()
                is ChapterTransition.Prev -> viewer.host.loadPreviousChapter()
            }
        }
    }
}
