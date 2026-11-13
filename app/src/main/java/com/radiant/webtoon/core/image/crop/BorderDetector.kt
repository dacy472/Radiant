package com.radiant.webtoon.core.image.crop

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure pixel-array detection of solid-colour borders (white, black or any near-uniform colour).
 *
 * This is deliberately free of Android types so it can run on the JVM in unit tests and is shared
 * by [CropBordersDecoder] (Coil) and the SubsamplingScaleImageView region decoder.
 *
 * Safety rules — the goal is to *never* remove artwork or text:
 *  - Only edge bands that are uniform within [Params.tolerance] on every channel are stripped; the
 *    scan stops at the first row/column that contains any deviating pixel.
 *  - A fully uniform image is never cropped.
 *  - Each axis must keep at least [Params.minContentFraction] of its size and [Params.minContentPx]
 *    pixels; otherwise that axis is left untouched (image is "mostly uniform").
 *  - The detected band is shrunk by a small safety margin so anti-aliased edges survive.
 */
object BorderDetector {

    data class Params(
        /** Maximum per-channel (A, R, G, B) difference for a pixel to count as border colour. */
        val tolerance: Int = 16,
        val minContentFraction: Float = 0.3f,
        val minContentPx: Int = 24,
        val safetyMarginFraction: Float = 0.004f,
        val safetyMarginMinPx: Int = 2,
    )

    /**
     * @param pixels ARGB pixels in row-major order (`width * height`, tightly packed).
     */
    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: Params = Params(),
    ): BordersPaddings {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return BordersPaddings.NONE

        // ── Vertical bands (top / bottom) ───────────────────────────────────────────────
        val refTop = pixels[0]
        var firstContentRow = 0
        while (firstContentRow < height && rowIsUniform(pixels, width, firstContentRow, 0, width, refTop, params.tolerance)) {
            firstContentRow++
        }
        // Whole image is one solid colour (w.r.t. the top reference): never crop.
        if (firstContentRow >= height) return BordersPaddings.NONE

        val refBottom = pixels[(height - 1) * width]
        var lastContentRow = height - 1
        while (lastContentRow > firstContentRow &&
            rowIsUniform(pixels, width, lastContentRow, 0, width, refBottom, params.tolerance)
        ) {
            lastContentRow--
        }

        val marginY = margin(height, params)
        var top = max(0, firstContentRow - marginY)
        var bottom = max(0, (height - 1 - lastContentRow) - marginY)
        val contentHeight = height - top - bottom
        if (contentHeight < minContent(height, params)) {
            top = 0
            bottom = 0
        }

        // ── Horizontal bands (left / right), measured only over rows that carry content ──
        val refLeft = pixels[firstContentRow * width]
        var firstContentCol = 0
        while (firstContentCol < width &&
            colIsUniform(pixels, width, firstContentCol, firstContentRow, lastContentRow, refLeft, params.tolerance)
        ) {
            firstContentCol++
        }

        var left = 0
        var right = 0
        if (firstContentCol < width) {
            val refRight = pixels[firstContentRow * width + (width - 1)]
            var lastContentCol = width - 1
            while (lastContentCol > firstContentCol &&
                colIsUniform(pixels, width, lastContentCol, firstContentRow, lastContentRow, refRight, params.tolerance)
            ) {
                lastContentCol--
            }
            val marginX = margin(width, params)
            left = max(0, firstContentCol - marginX)
            right = max(0, (width - 1 - lastContentCol) - marginX)
            if (width - left - right < minContent(width, params)) {
                left = 0
                right = 0
            }
        }

        return BordersPaddings(left, top, right, bottom)
    }

    private fun margin(size: Int, params: Params): Int =
        max(params.safetyMarginMinPx, (size * params.safetyMarginFraction).toInt())

    private fun minContent(size: Int, params: Params): Int =
        max(params.minContentPx, (size * params.minContentFraction).toInt()).coerceAtMost(size)

    private fun rowIsUniform(
        pixels: IntArray,
        width: Int,
        y: Int,
        x0: Int,
        x1: Int,
        ref: Int,
        tolerance: Int,
    ): Boolean {
        val base = y * width
        for (x in x0 until x1) {
            if (!isClose(pixels[base + x], ref, tolerance)) return false
        }
        return true
    }

    private fun colIsUniform(
        pixels: IntArray,
        width: Int,
        x: Int,
        y0: Int,
        y1: Int,
        ref: Int,
        tolerance: Int,
    ): Boolean {
        for (y in y0..y1) {
            if (!isClose(pixels[y * width + x], ref, tolerance)) return false
        }
        return true
    }

    private fun isClose(a: Int, b: Int, tolerance: Int): Boolean {
        if (a == b) return true
        return abs((a ushr 24) - (b ushr 24)) <= tolerance &&
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance
    }
}
