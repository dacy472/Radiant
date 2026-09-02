package com.radiant.webtoon.ui.reader

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * A speech-bubble region detected near the bottom of an image slice.
 * Coordinates are in source bitmap pixels.
 */
data class BottomBubble(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
) {
    val widthPx: Int get() = rightPx - leftPx
    val heightPx: Int get() = bottomPx - topPx
}

/**
 * Heuristic detector for white speech bubbles anchored to the bottom of a slice.
 */
object TextBubbleDetector {
    private const val MIN_BUBBLE_HEIGHT_PX = 24
    private const val MIN_BUBBLE_WIDTH_RATIO = 0.18f
    private const val SCAN_HEIGHT_RATIO = 0.28f
    private const val WHITE_LUMINANCE_THRESHOLD = 210
    private const val ROW_WHITE_RATIO_THRESHOLD = 0.42f

    fun detectBottomBubble(bitmap: Bitmap): BottomBubble? {
        if (bitmap.width < 8 || bitmap.height < 32) return null

        val scanHeight = max(
            MIN_BUBBLE_HEIGHT_PX * 2,
            (bitmap.height * SCAN_HEIGHT_RATIO).toInt(),
        ).coerceAtMost(bitmap.height)

        val scanTop = bitmap.height - scanHeight
        var clusterTop = -1
        var clusterBottom = -1
        var clusterLeft = bitmap.width
        var clusterRight = 0

        for (y in bitmap.height - 1 downTo scanTop) {
            val row = rowWhiteMetrics(bitmap, y)
            val isBubbleRow = row.whiteRatio >= ROW_WHITE_RATIO_THRESHOLD &&
                row.spanWidth >= bitmap.width * MIN_BUBBLE_WIDTH_RATIO

            if (isBubbleRow) {
                if (clusterBottom == -1) clusterBottom = y + 1
                clusterTop = y
                clusterLeft = min(clusterLeft, row.left)
                clusterRight = max(clusterRight, row.right)
            } else if (clusterBottom != -1) {
                break
            }
        }

        if (clusterBottom == -1 || clusterTop == -1) return null

        val height = clusterBottom - clusterTop
        val width = clusterRight - clusterLeft
        if (height < MIN_BUBBLE_HEIGHT_PX || width < bitmap.width * MIN_BUBBLE_WIDTH_RATIO) {
            return null
        }

        return BottomBubble(
            leftPx = clusterLeft.coerceAtLeast(0),
            topPx = clusterTop.coerceAtLeast(0),
            rightPx = clusterRight.coerceAtMost(bitmap.width),
            bottomPx = clusterBottom.coerceAtMost(bitmap.height),
        )
    }

    private data class RowWhiteMetrics(
        val whiteRatio: Float,
        val spanWidth: Int,
        val left: Int,
        val right: Int,
    )

    private fun rowWhiteMetrics(bitmap: Bitmap, y: Int): RowWhiteMetrics {
        var whiteCount = 0
        var left = bitmap.width
        var right = 0
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            if (luminance >= WHITE_LUMINANCE_THRESHOLD) {
                whiteCount++
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        val spanWidth = if (right >= left) right - left + 1 else 0
        return RowWhiteMetrics(
            whiteRatio = whiteCount.toFloat() / bitmap.width.toFloat(),
            spanWidth = spanWidth,
            left = left,
            right = right + 1,
        )
    }
}
