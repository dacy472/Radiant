/*
 * Structure adapted from Mihon's `eu.kanade.tachiyomi.ui.reader.viewer.Viewer`
 * (https://github.com/mihonapp/mihon), Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.radiant.webtoon.ui.reader.loader.ReaderPageLoader
import com.radiant.webtoon.ui.reader.model.ReaderPage
import com.radiant.webtoon.ui.reader.model.ViewerChapters
import kotlinx.coroutines.CoroutineScope

/** A swappable page renderer hosted by [com.radiant.webtoon.ui.reader.ReaderActivity]. */
interface Viewer {

    /** The view hosting this viewer's content. */
    fun getView(): View

    /** Called when the reader closes or the viewer is swapped for another reading mode. */
    fun destroy() {}

    /** Sets the active chapter (its pages plus prev/next transitions). */
    fun setChapters(chapters: ViewerChapters)

    /** Scrolls/pages to [page]. */
    fun moveToPage(page: ReaderPage)

    /** Re-requests every page currently showing an error (used after the WebView challenge). */
    fun retryFailedPages() {}

    /** Return `true` when the key event was consumed. */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /** Return `true` when the motion event was consumed. */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}

/** Immutable snapshot of the reader preferences viewers care about. */
data class ViewerSettings(
    val tapZones: Boolean = true,
    val volumeKeys: Boolean = false,
    /** Crop Borders for *this* viewer family (paged or webtoon), already resolved by the activity. */
    val cropBorders: Boolean = false,
    val usePageTransitions: Boolean = true,
    /** ARGB colour used for transition text so it stays legible on light/dark reader backgrounds. */
    val contentColor: Int = 0xFFFFFFFF.toInt(),
)

/** What a viewer needs from the activity (kept as an interface so viewers stay decoupled from it). */
interface ReaderViewerHost {
    val context: Context
    val scope: CoroutineScope
    val pageLoader: ReaderPageLoader
    val viewerSettings: ViewerSettings
    val isMenuVisible: Boolean

    /** Zero-based page of the active chapter became the current one. */
    fun onPageSelected(page: ReaderPage)

    /**
     * Continuous viewers only: [page] is the top-most visible page and [offsetPermyriad] how far
     * (1/10000 of its height) the viewport top sits inside it. Becomes the saved reading position.
     */
    fun onReadPositionChanged(page: ReaderPage, offsetPermyriad: Int) {}
    fun toggleMenu()
    fun showMenu()
    fun hideMenu()
    fun loadNextChapter()
    fun loadPreviousChapter()

    /**
     * Opens the existing Cloudflare "Solve challenge in WebView" flow (the same bypass activity the
     * reader screen used) for [url]; [referer] is the source origin.
     */
    fun solveCloudflareChallenge(url: String?, referer: String?)

    /** Source origin used as WebView referer, if known. */
    val sourceReferer: String?
}
