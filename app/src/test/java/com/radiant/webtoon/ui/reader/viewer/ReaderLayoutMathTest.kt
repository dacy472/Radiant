package com.radiant.webtoon.ui.reader.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLayoutMathTest {

    @Test
    fun pageHeightFollowsTheImageAspectRatioAtTheViewportWidth() {
        // 800×5000 page fit to a 1080-wide strip: 5000/800*1080 = 6750.
        assertEquals(6750, ReaderLayoutMath.pageHeightForWidth(1080, 800, 5000))
        // Truncate, matching SubsamplingScaleImageView's wrap-content measure.
        assertEquals(66, ReaderLayoutMath.pageHeightForWidth(100, 3, 2))
    }

    @Test
    fun pageHeightIsZeroWhenADimensionIsMissing() {
        assertEquals(0, ReaderLayoutMath.pageHeightForWidth(0, 800, 5000))
        assertEquals(0, ReaderLayoutMath.pageHeightForWidth(1080, 0, 5000))
        assertEquals(0, ReaderLayoutMath.pageHeightForWidth(1080, 800, 0))
    }

    @Test
    fun placeholderIsAtLeastOneViewportTall() {
        assertEquals(1920, ReaderLayoutMath.placeholderHeight(1080, 1920))
        assertEquals(400, ReaderLayoutMath.placeholderHeight(400, 200))
    }
}
