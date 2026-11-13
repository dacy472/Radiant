package com.radiant.webtoon.ui.reader.model

import com.radiant.webtoon.core.source.PageRefs

/** Minimal chapter identity the viewers need (the id is the chapter `url` used everywhere else). */
data class ReaderChapterInfo(val id: String, val name: String)

/**
 * One page of the active chapter. [url] is whatever [com.radiant.webtoon.ui.reader.ReaderViewModel]
 * produced: an http(s) image URL, a deferred extension page ref ([PageRefs]), a `file://` path, or a
 * `content://` SAF document.
 *
 * Data-class equality (index + url + chapter) is what RecyclerView's DiffUtil and the pager adapter
 * use to track items across [ViewerChapters] updates.
 */
data class ReaderPage(
    val index: Int,
    val url: String,
    val chapterId: String,
) {
    val number: Int get() = index + 1
    val isLocal: Boolean get() = !PageRefs.isRemote(url)
}

/**
 * Item shown between chapters. [to] is `null` when there is no adjacent chapter in that direction.
 * Unlike Mihon, adjacent chapters are not preloaded into the same adapter: the transition offers a
 * button that asks the view model to load the neighbouring chapter (same behaviour as the Compose
 * reader's "Next chapter" button).
 */
sealed class ChapterTransition {
    abstract val from: ReaderChapterInfo
    abstract val to: ReaderChapterInfo?

    data class Prev(override val from: ReaderChapterInfo, override val to: ReaderChapterInfo?) : ChapterTransition()
    data class Next(override val from: ReaderChapterInfo, override val to: ReaderChapterInfo?) : ChapterTransition()
}

/** Everything a viewer needs to display the active chapter. */
data class ViewerChapters(
    val current: ReaderChapterInfo,
    val pages: List<ReaderPage>,
    val prev: ReaderChapterInfo?,
    val next: ReaderChapterInfo?,
    /** Zero-based page to open on first layout. */
    val requestedPage: Int,
    /** Position inside [requestedPage] in 1/10000 of its height; only webtoon viewers use it. */
    val requestedOffset: Int = 0,
    /** Load generation of the view model; guards stale callbacks after a chapter switch. */
    val generation: Long,
)
