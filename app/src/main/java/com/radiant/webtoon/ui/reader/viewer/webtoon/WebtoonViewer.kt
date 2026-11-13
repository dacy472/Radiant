/*
 * Structure adapted from Mihon's `WebtoonViewer` (https://github.com/mihonapp/mihon),
 * Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.radiant.webtoon.ui.reader.model.ChapterTransition
import com.radiant.webtoon.ui.reader.model.ReaderChapterInfo
import com.radiant.webtoon.ui.reader.model.ReaderPage
import com.radiant.webtoon.ui.reader.model.ViewerChapters
import com.radiant.webtoon.ui.reader.viewer.MenuOnlyNavigation
import com.radiant.webtoon.ui.reader.viewer.NavigationRegion
import com.radiant.webtoon.ui.reader.viewer.ReaderLayoutMath
import com.radiant.webtoon.ui.reader.viewer.ReaderViewerHost
import com.radiant.webtoon.ui.reader.viewer.Viewer
import com.radiant.webtoon.ui.reader.viewer.WebtoonNavigation
import kotlin.math.max

/**
 * Gapless long-strip reader: pages are stacked edge to edge, each fit to the viewport width.
 *
 * [ContinuousVerticalViewer] is the same class with [pageSpacingDp] > 0 (the only difference
 * Mihon makes between "Webtoon" and "Continuous vertical").
 */
open class WebtoonViewer(
    val host: ReaderViewerHost,
    pageSpacingDp: Int = 0,
) : Viewer {

    /** Space between consecutive pages/transition cards. `0` for the webtoon mode. */
    val pageSpacingPx: Int = (pageSpacingDp * host.context.resources.displayMetrics.density + 0.5f).toInt()

    private val recycler = WebtoonRecyclerView(host.context)
    private val frame = WebtoonFrame(host.context)
    private val layoutManager = WebtoonLayoutManager(host.context)
    private val adapter = WebtoonAdapter(this)

    private var chapters: ViewerChapters? = null
    private var currentItem: Any? = null

    /** Saved in-page offset waiting for its page to reach the final image height. */
    private var pendingRestore: PendingRestore? = null
    private var lastReportedPosition: Pair<Int, Int>? = null

    private data class PendingRestore(val page: ReaderPage, val offsetPermyriad: Int)

    init {
        // Zoom is a scale transform. Leave it unclipped so a pinch does not cut the strip off.
        frame.clipChildren = false
        recycler.apply {
            setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
            isFocusable = false
            itemAnimator = null
            layoutManager = this@WebtoonViewer.layoutManager
            adapter = this@WebtoonViewer.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = onScrolledInternal(dy)

                    override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                        // The reader took over; never yank them back to the saved spot.
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) pendingRestore = null
                    }
                },
            )
            tapListener = { event -> onTap(event) }
        }
        frame.addView(recycler, MATCH_PARENT, MATCH_PARENT)
    }

    override fun getView(): View = frame

    fun placeholderHeightPx(): Int {
        val m = host.context.resources.displayMetrics
        val w = frame.width.takeIf { it > 0 } ?: m.widthPixels
        val h = frame.height.takeIf { it > 0 } ?: m.heightPixels
        return ReaderLayoutMath.placeholderHeight(w, h)
    }

    override fun destroy() {
        recycler.adapter = null
        recycler.clearOnScrollListeners()
    }

    override fun setChapters(chapters: ViewerChapters) {
        val previous = this.chapters
        this.chapters = chapters
        currentItem = null
        recycler.resetZoom()

        val items = buildList<Any> {
            add(ChapterTransition.Prev(chapters.current, chapters.prev))
            addAll(chapters.pages)
            add(ChapterTransition.Next(chapters.current, chapters.next))
        }
        adapter.setItems(items)

        val isNewChapter = previous == null ||
            previous.current.id != chapters.current.id ||
            previous.generation != chapters.generation
        if (isNewChapter && chapters.pages.isNotEmpty()) {
            val page = chapters.pages.getOrNull(chapters.requestedPage) ?: chapters.pages.first()
            lastReportedPosition = null
            pendingRestore = chapters.requestedOffset
                .takeIf { it > 0 && page.index == chapters.requestedPage }
                ?.let { PendingRestore(page, it) }
            scrollToPosition(adapterPositionOf(page))
        }
    }

    override fun moveToPage(page: ReaderPage) {
        pendingRestore = null
        val position = adapterPositionOf(page)
        if (position >= 0) scrollToPosition(position)
    }

    /** Called by a page holder once its image height is final; applies a pending saved offset. */
    fun onPageHeightFinal(page: ReaderPage) {
        val restore = pendingRestore ?: return
        if (restore.page != page) return
        val position = adapterPositionOf(page)
        val view = layoutManager.findViewByPosition(position) ?: return
        val height = view.height
        if (height <= 0) return
        pendingRestore = null
        val offsetPx = (height.toLong() * restore.offsetPermyriad / 10_000L).toInt()
        layoutManager.scrollToPositionWithOffset(position, -offsetPx)
    }

    private fun adapterPositionOf(page: ReaderPage): Int {
        // [0] is the "previous chapter" transition.
        val c = chapters ?: return -1
        val idx = c.pages.indexOfFirst { it.index == page.index }
        return if (idx < 0) -1 else idx + 1
    }

    private fun scrollToPosition(position: Int) {
        if (position < 0) return
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    private fun onScrolledInternal(dy: Int) {
        val position = layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position) ?: return
        if (item != currentItem) {
            currentItem = item
            if (item is ReaderPage) host.onPageSelected(item)
        }
        // Reaching the very end of the last chapter reveals the chrome (like Mihon).
        if (dy > 0 && item is ChapterTransition.Next && item.to == null) host.showMenu()
        reportReadPosition()
    }

    private fun reportReadPosition() {
        if (pendingRestore != null) return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val page = adapter.items.getOrNull(first) as? ReaderPage ?: return
        val view = layoutManager.findViewByPosition(first) ?: return
        val loaded = (recycler.getChildViewHolder(view) as? WebtoonPageHolder)
            ?.view?.state == com.radiant.webtoon.ui.reader.viewer.ReaderPageView.State.READY
        val offset = if (loaded && view.height > 0 && view.top < 0) {
            ((-view.top).toLong() * 10_000L / view.height).toInt().coerceIn(0, 9_999)
        } else {
            0
        }
        val position = page.index to offset
        if (position == lastReportedPosition) return
        lastReportedPosition = position
        host.onReadPositionChanged(page, offset)
    }

    private fun onTap(event: MotionEvent) {
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val w = frame.width.coerceAtLeast(1)
        val h = frame.height.coerceAtLeast(1)
        val pos = PointF((event.rawX - loc[0]) / w, (event.rawY - loc[1]) / h)
        val nav = if (host.viewerSettings.tapZones) WebtoonNavigation else MenuOnlyNavigation
        when (nav.getAction(pos)) {
            NavigationRegion.MENU -> host.toggleMenu()
            NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
            NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
        }
    }

    private fun scrollDown() {
        pendingRestore = null
        recycler.smoothScrollBy(0, max(1, recycler.height * 3 / 4))
    }

    private fun scrollUp() {
        pendingRestore = null
        recycler.smoothScrollBy(0, -max(1, recycler.height * 3 / 4))
    }

    override fun retryFailedPages() {
        for (i in 0 until recycler.childCount) {
            (recycler.getChildViewHolder(recycler.getChildAt(i)) as? WebtoonPageHolder)?.view?.retryIfFailed()
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val volume = host.viewerSettings.volumeKeys
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volume) {
                if (isUp) scrollDown()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> if (volume) {
                if (isUp) scrollUp()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) host.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (isUp) scrollDown()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (isUp) scrollUp()
                return true
            }
        }
        return false
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (dy != 0f) {
                pendingRestore = null
                recycler.scrollBy(0, (-dy * recycler.height / 8f).toInt())
                return true
            }
        }
        return false
    }

    /** Test/debug hook: the chapter-info shown by the first transition. */
    internal fun currentChapter(): ReaderChapterInfo? = chapters?.current

    private companion object {
        const val RECYCLER_VIEW_CACHE_SIZE = 4
    }
}

/**
 * "Continuous vertical" reading mode.
 *
 * Differs from [WebtoonViewer] only in that every page and chapter-transition card is separated
 * by a 15dp margin (Mihon: `WebtoonViewer(isContinuous = false)`), where the webtoon mode stacks
 * pages with no gap at all so full-bleed strips look like one image. Margins are real layout
 * margins of the RecyclerView items, so pages still never overlap.
 */
class ContinuousVerticalViewer(host: ReaderViewerHost) : WebtoonViewer(host, pageSpacingDp = SPACING_DP) {
    companion object {
        const val SPACING_DP = 15
    }
}
