package com.radiant.webtoon.core.image.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BorderDetectorTest {

    @Test
    fun stripsUniformEdgeBandsAndKeepsTheArtwork() {
        val width = 20
        val height = 16
        val pixels = IntArray(width * height) { WHITE }
        fill(pixels, width, left = 4, top = 3, right = 16, bottom = 13, color = BLACK)
        val borders = BorderDetector.detect(
            pixels,
            width,
            height,
            BorderDetector.Params(
                tolerance = 0,
                minContentFraction = 0.1f,
                minContentPx = 1,
                safetyMarginFraction = 0f,
                safetyMarginMinPx = 0,
            ),
        )
        assertEquals(BordersPaddings(left = 4, top = 3, right = 4, bottom = 3), borders)
    }

    @Test
    fun doesNotCropAFullyUniformImage() {
        val pixels = IntArray(12 * 10) { WHITE }
        val borders = BorderDetector.detect(pixels, 12, 10)
        assertTrue(borders.isEmpty)
    }

    @Test
    fun defaultSafetyMarginShrinksTheDetectedBand() {
        val width = 100
        val height = 100
        val pixels = IntArray(width * height) { WHITE }
        fill(pixels, width, left = 10, top = 10, right = 90, bottom = 90, color = BLACK)
        val borders = BorderDetector.detect(pixels, width, height)
        assertEquals(BordersPaddings(left = 8, top = 8, right = 8, bottom = 8), borders)
    }

    @Test
    fun leavesAnAxisAloneWhenTheBandWouldRemoveMostOfTheImage() {
        val width = 100
        val height = 100
        val pixels = IntArray(width * height) { WHITE }
        fill(pixels, width, left = 45, top = 45, right = 55, bottom = 55, color = BLACK)
        val borders = BorderDetector.detect(pixels, width, height)
        assertTrue(borders.isEmpty)
    }

    @Test
    fun fromSampledScalesBoundariesOutwardToFullResolution() {
        val sampled = BordersPaddings(left = 2, top = 1, right = 1, bottom = 3)
        val full = BordersPaddings.fromSampled(
            sampled = sampled,
            sampleSize = 4,
            sampledWidth = 10,
            sampledHeight = 8,
            fullWidth = 40,
            fullHeight = 32,
        )
        assertEquals(BordersPaddings(left = 8, top = 4, right = 4, bottom = 12), full)
    }

    @Test
    fun sampleSizeStaysWithinTheDetectionPixelBudget() {
        assertEquals(1, BordersPaddings.sampleSizeFor(100, 100))
        assertEquals(4, BordersPaddings.sampleSizeFor(4000, 4000))
    }

    private fun fill(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int,
    ) {
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = color
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
