/*
 * Structure adapted from Mihon's `PagerViewer` / `L2RPagerViewer` / `R2LPagerViewer` /
 * `VerticalPagerViewer` (https://github.com/mihonapp/mihon), Apache License 2.0.
 * Uses ViewPager2 (RecyclerView based) instead of Mihon's ViewPager fork.
 */
package com.radiant.webtoon.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.radiant.webtoon.ui.reader.model.ChapterTransition
import com.radiant.webtoon.ui.reader.model.ReaderPage
import com.radiant.webtoon.ui.reader.model.ViewerChapters
import com.radiant.webtoon.ui.reader.viewer.GestureDetectorWithLongTap
import com.radiant.webtoon.ui.reader.viewer.MenuOnlyNavigation
import com.radiant.webtoon.ui.reader.viewer.NavigationRegion
import com.radiant.webtoon.ui.reader.viewer.PagedNavigation
import com.radiant.webtoon.ui.reader.viewer.ReaderPageView
import com.radiant.webtoon.ui.reader.viewer.ReaderTransitionView
import com.radiant.webtoon.ui.reader.viewer.ReaderViewerHost
import com.radiant.webtoon.ui.reader.viewer.VerticalPagerNavigation
import com.radiant.webtoon.ui.reader.viewer.Viewer
import com.radiant.webtoon.ui.reader.viewer.ViewerNavigation

/** One page per screen, swiped with a [ViewPager2]. Subclasses fix orientation / direction. */
abstract class PagerViewer(
    val host: ReaderViewerHost,
    private val vertical: Boolean,
    private val rtl: Boolean,
) : Viewer {

    private val pager = ViewPager2(host.context)
    private val frame = PagerFrame(host.context, host, pager) { event -> onTap(event) }
    private val adapter = PagerViewerAdapter(this)

    private var chapters: ViewerChapters? = null
    private var items: List<Any> = emptyList()
    private var lastReported: Any? = null

    private val navigation: ViewerNavigation
        get() = if (!host.viewerSettings.tapZones) MenuOnlyNavigation else if (vertical) VerticalPagerNavigation else PagedNavigation

    init {
        pager.apply {
            orientation = if (vertical) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            offscreenPageLimit = OFFSCREEN_PAGES
            adapter = this@PagerViewer.adapter
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = onPositionSelected(position)
                },
            )
        }
        frame.addView(pager, MATCH_PARENT, MATCH_PARENT)
    }

    override fun getView(): View = frame

    override fun destroy() {
        pager.adapter = null
    }

    override fun setChapters(chapters: ViewerChapters) {
        val previous = this.chapters
        this.chapters = chapters
        val newItems = buildList<Any> {
            add(ChapterTransition.Prev(chapters.current, chapters.prev))
            addAll(chapters.pages)
            add(ChapterTransition.Next(chapters.current, chapters.next))
        }
        val isNewChapter = previous == null ||
            previous.current.id != chapters.current.id ||
            previous.generation != chapters.generation
        items = newItems
        lastReported = null
        adapter.setItems(newItems)
        if (isNewChapter && chapters.pages.isNotEmpty()) {
            val idx = chapters.requestedPage.coerceIn(0, chapters.pages.size - 1)
            pager.setCurrentItem(idx + 1, false)
            // setCurrentItem to the same position doesn't fire the callback.
            chapters.pages.getOrNull(idx)?.let { host.onPageSelected(it) }
        }
    }

    override fun moveToPage(page: ReaderPage) {
        val idx = items.indexOfFirst { it is ReaderPage && it.index == page.index }
        if (idx >= 0) pager.setCurrentItem(idx, false)
    }

    private fun onPositionSelected(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (item == lastReported) return
        lastReported = item
        when (item) {
            is ReaderPage -> host.onPageSelected(item)
            is ChapterTransition.Next -> if (item.to == null) host.showMenu()
            else -> Unit
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────────────

    private fun onTap(event: MotionEvent) {
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val pos = PointF(
            (event.rawX - loc[0]) / frame.width.coerceAtLeast(1),
            (event.rawY - loc[1]) / frame.height.coerceAtLeast(1),
        )
        when (navigation.getAction(pos)) {
            NavigationRegion.MENU -> host.toggleMenu()
            NavigationRegion.NEXT -> moveToNext()
            NavigationRegion.PREV -> moveToPrevious()
            NavigationRegion.LEFT -> if (rtl) moveRightOrNext() else moveLeftOrPrevious()
            NavigationRegion.RIGHT -> if (rtl) moveLeftOrPrevious() else moveRightOrNext()
        }
    }

    /** Tap on the left edge: pan left if the page is zoomed and can pan, else previous page (L2R). */
    private fun moveLeftOrPrevious() {
        val image = currentHolder()?.view?.imageView
        if (image?.canPanLeft() == true) image.panLeft()
        else if (rtl) moveToNext() else moveToPrevious()
    }

    private fun moveRightOrNext() {
        val image = currentHolder()?.view?.imageView
        if (image?.canPanRight() == true) image.panRight()
        else if (rtl) moveToPrevious() else moveToNext()
    }

    fun moveToNext() {
        val n = pager.currentItem + 1
        if (n < adapter.itemCount) pager.setCurrentItem(n, true)
    }

    fun moveToPrevious() {
        val n = pager.currentItem - 1
        if (n >= 0) pager.setCurrentItem(n, true)
    }

    private fun currentHolder(): PagerPageHolder? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(pager.currentItem) as? PagerPageHolder
    }

    override fun retryFailedPages() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            (rv.getChildViewHolder(rv.getChildAt(i)) as? PagerPageHolder)?.view?.retryIfFailed()
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val volume = host.viewerSettings.volumeKeys
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volume) {
                if (isUp) moveToNext()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (volume) {
                if (isUp) moveToPrevious()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) host.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) if (rtl && !vertical) moveToPrevious() else moveToNext()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) if (rtl && !vertical) moveToNext() else moveToPrevious()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isUp) moveToNext()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (isUp) moveToPrevious()
                return true
            }
        }
        return false
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    private companion object {
        const val OFFSCREEN_PAGES = 1
    }
}

class L2RPagerViewer(host: ReaderViewerHost) : PagerViewer(host, vertical = false, rtl = false)
class R2LPagerViewer(host: ReaderViewerHost) : PagerViewer(host, vertical = false, rtl = true)
class VerticalPagerViewer(host: ReaderViewerHost) : PagerViewer(host, vertical = true, rtl = false)

/**
 * Wrapper that watches (but never consumes) every touch event for taps, so they work on top of the
 * pages' own zoom gestures. Taps that start on a button (retry / challenge / load chapter) are not
 * treated as navigation taps.
 */
class PagerFrame(
    context: android.content.Context,
    private val host: ReaderViewerHost,
    private val target: View,
    private val onTap: (MotionEvent) -> Unit,
) : FrameLayout(context) {

    private var downOnButton = false

    private val detector = GestureDetectorWithLongTap(
        context,
        object : GestureDetectorWithLongTap.Listener() {
            override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
                if (!downOnButton) onTap(ev)
                return false
            }
        },
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnButton = isOverButton(this, ev.rawX, ev.rawY)
        }
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isOverButton(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        if (rawX < loc[0] || rawX > loc[0] + view.width || rawY < loc[1] || rawY > loc[1] + view.height) return false
        if (view is Button) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isOverButton(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }
}

class PagerViewerAdapter(private val viewer: PagerViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<Any> = emptyList()

    fun setItems(newItems: List<Any>) {
        val old = items
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]
                    val b = newItems[n]
                    return when {
                        a is ReaderPage && b is ReaderPage -> a == b
                        a is ChapterTransition.Prev && b is ChapterTransition.Prev -> a.from.id == b.from.id
                        a is ChapterTransition.Next && b is ChapterTransition.Next -> a.from.id == b.from.id
                        else -> false
                    }
                }

                override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
            },
        )
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (items[position] is ReaderPage) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val lp = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        return if (viewType == 0) {
            PagerPageHolder(ReaderPageView(parent.context, viewer.host, isWebtoon = false).also { it.layoutParams = lp })
        } else {
            PagerTransitionHolder(ReaderTransitionView(parent.context).also { it.layoutParams = lp }, viewer)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PagerPageHolder -> holder.view.bind(items[position] as ReaderPage)
            is PagerTransitionHolder -> holder.bind(items[position] as ChapterTransition)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is PagerPageHolder) holder.view.recycle()
        super.onViewRecycled(holder)
    }
}

class PagerPageHolder(val view: ReaderPageView) : RecyclerView.ViewHolder(view)

class PagerTransitionHolder(
    private val view: ReaderTransitionView,
    private val viewer: PagerViewer,
) : RecyclerView.ViewHolder(view) {
    fun bind(transition: ChapterTransition) {
        view.bind(transition, viewer.host.viewerSettings.contentColor) {
            when (transition) {
                is ChapterTransition.Next -> viewer.host.loadNextChapter()
                is ChapterTransition.Prev -> viewer.host.loadPreviousChapter()
            }
        }
    }
}
