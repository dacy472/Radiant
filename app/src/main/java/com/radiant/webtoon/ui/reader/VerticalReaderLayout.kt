package com.radiant.webtoon.ui.reader

import com.radiant.webtoon.utils.ImageSlice

/**
 * Layout metadata for one vertically stacked reader segment.
 */
data class ReaderSegmentLayout(
    val slice: ImageSlice,
    val displayHeightPx: Int,
    val translationYPx: Float = 0f,
    val bubble: BottomBubble? = null,
)

object VerticalReaderLayout {
    /**
     * Builds a sequential vertical layout from measured slice heights.
     *
     * When a slice has a bottom speech bubble, the next segment receives
     * [ReaderSegmentLayout.translationYPx] equal to the bubble's display height so
     * readers can see the full bubble before the following artwork — no hardcoded gaps.
     */
    fun build(
        slices: List<ImageSlice>,
        screenWidthPx: Int,
        bubbles: List<BottomBubble?>,
    ): List<ReaderSegmentLayout> {
        if (slices.isEmpty()) return emptyList()

        val scale = screenWidthPx.toFloat() / slices.first().bitmap.width.toFloat()
        val displayHeights = slices.map { slice ->
            (slice.bitmap.height * scale).toInt().coerceAtLeast(1)
        }

        val translations = MutableList(slices.size) { 0f }
        for (index in 0 until slices.lastIndex) {
            val bubble = bubbles.getOrNull(index) ?: continue
            val bubbleDisplayHeight = (bubble.heightPx * scale).coerceAtLeast(0f)
            translations[index + 1] = bubbleDisplayHeight
        }

        return slices.mapIndexed { index, slice ->
            ReaderSegmentLayout(
                slice = slice,
                displayHeightPx = displayHeights[index],
                translationYPx = translations[index],
                bubble = bubbles.getOrNull(index),
            )
        }
    }

    /**
     * Keeps a bottom bubble fully on screen by shifting it upward when its bottom edge
     * would extend past [viewportBottomPx].
     */
    fun clampBubbleOffset(
        bubbleBottomInWindowPx: Float,
        viewportBottomPx: Float,
        marginPx: Float = 0f,
    ): Float {
        val overflow = bubbleBottomInWindowPx - (viewportBottomPx - marginPx)
        return if (overflow > 0f) -overflow else 0f
    }

    /** Total scroll height contributed by a segment including its leading gap. */
    fun segmentScrollExtent(layout: ReaderSegmentLayout): Int {
        return layout.displayHeightPx + layout.translationYPx.toInt().coerceAtLeast(0)
    }

    fun totalScrollExtent(layouts: List<ReaderSegmentLayout>): Int {
        return layouts.sumOf { segmentScrollExtent(it) }
    }
}
