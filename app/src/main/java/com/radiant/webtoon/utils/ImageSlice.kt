package com.radiant.webtoon.utils

import android.graphics.Bitmap

/**
 * A vertical slice of a source page image.
 *
 * [sourceTop] is inclusive, [sourceBottom] is exclusive — consecutive slices tile the
 * full source height with no gaps or overlaps.
 */
data class ImageSlice(
    val bitmap: Bitmap,
    val sourceTop: Int,
    val sourceBottom: Int,
) {
    val sourceHeight: Int get() = sourceBottom - sourceTop

    init {
        require(sourceTop >= 0) { "sourceTop must be non-negative" }
        require(sourceBottom > sourceTop) { "sourceBottom must be greater than sourceTop" }
        require(bitmap.height == sourceHeight) {
            "Bitmap height (${bitmap.height}) must match slice height ($sourceHeight)"
        }
    }
}

object ImageSliceCoverage {
    /** Returns true when slices exactly cover [imageHeight] from 0..height with no gaps. */
    fun isComplete(imageHeight: Int, slices: List<ImageSlice>): Boolean =
        isCompleteRanges(imageHeight, slices.map { it.sourceTop to it.sourceBottom })

    fun isCompleteRanges(imageHeight: Int, ranges: List<Pair<Int, Int>>): Boolean {
        if (imageHeight <= 0 || ranges.isEmpty()) return false
        var expectedTop = 0
        for ((top, bottom) in ranges) {
            if (top != expectedTop || bottom <= top) return false
            expectedTop = bottom
        }
        return expectedTop == imageHeight
    }
}
