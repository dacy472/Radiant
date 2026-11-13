package com.radiant.webtoon.ui.reader.viewer

import kotlin.math.max

/**
 * Pure layout maths shared by the webtoon page holders (and unit-tested on the JVM).
 */
object ReaderLayoutMath {

    /**
     * Height of a page rendered fit-to-width: `viewWidth * imageHeight / imageWidth`, truncated the
     * same way [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView] measures its
     * wrap-content height, so a page's measured height always equals its rendered height and
     * stacked pages never overlap or leave gaps. Image dimensions are those the decoder reports,
     * i.e. already cropped when Crop Borders is on.
     */
    fun pageHeightForWidth(viewWidth: Int, imageWidth: Int, imageHeight: Int): Int {
        if (viewWidth <= 0 || imageWidth <= 0 || imageHeight <= 0) return 0
        return max(1, (imageHeight.toDouble() / imageWidth.toDouble() * viewWidth).toInt())
    }

    /**
     * Height reserved for a page that has not decoded yet. Keeping it at least one viewport tall
     * stops RecyclerView from inflating many empty holders at once (Mihon's behaviour).
     */
    fun placeholderHeight(viewportWidth: Int, viewportHeight: Int): Int =
        max(viewportHeight, (viewportWidth * MIN_PLACEHOLDER_ASPECT).toInt()).coerceAtLeast(1)

    private const val MIN_PLACEHOLDER_ASPECT = 1.0f
}
