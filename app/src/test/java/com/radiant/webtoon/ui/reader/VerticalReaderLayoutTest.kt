package com.radiant.webtoon.ui.reader

import com.radiant.webtoon.utils.ImageSliceCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalReaderLayoutTest {

    @Test
    fun bubbleTranslation_matchesScaledBubbleHeight() {
        val bubble = BottomBubble(leftPx = 40, topPx = 700, rightPx = 360, bottomPx = 780)
        val scale = 0.5f
        val gap = bubble.heightPx * scale
        assertEquals(40f, gap)
    }

    @Test
    fun clampBubbleOffset_shiftsUpWhenOverflowingViewport() {
        assertEquals(0f, VerticalReaderLayout.clampBubbleOffset(900f, 1000f))
        assertEquals(-50f, VerticalReaderLayout.clampBubbleOffset(1050f, 1000f))
    }

    @Test
    fun segmentScrollExtent_includesLeadingGap() {
        assertEquals(880, 800 + 80)
    }

    @Test
    fun sliceCoverage_requiresContiguousHalfOpenRanges() {
        assertTrue(
            ImageSliceCoverage.isCompleteRanges(
                5000,
                listOf(0 to 2048, 2048 to 4096, 4096 to 5000),
            ),
        )
        assertFalse(
            ImageSliceCoverage.isCompleteRanges(
                5000,
                listOf(0 to 2048, 2049 to 5000),
            ),
        )
    }
}
