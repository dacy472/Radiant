package com.radiant.webtoon.core.image.crop

/**
 * Pixel widths of the solid-colour margins found on each side of a decoded image.
 *
 * All values are in pixels of the bitmap the borders were measured on (or of the full-resolution
 * image once [fromSampled] has been applied). A value of `0` means "nothing to strip".
 */
data class BordersPaddings(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean get() = left <= 0 && top <= 0 && right <= 0 && bottom <= 0

    fun croppedWidth(imageWidth: Int): Int = imageWidth - left - right

    fun croppedHeight(imageHeight: Int): Int = imageHeight - top - bottom

    companion object {
        val NONE = BordersPaddings(0, 0, 0, 0)

        /**
         * Converts paddings measured on a bitmap decoded with `inSampleSize = [sampleSize]` back to
         * full-resolution pixels. Content boundaries are rounded *outwards*, so the result never
         * removes more than what was detected (a sampled pixel covers [sampleSize] real pixels).
         */
        fun fromSampled(
            sampled: BordersPaddings,
            sampleSize: Int,
            sampledWidth: Int,
            sampledHeight: Int,
            fullWidth: Int,
            fullHeight: Int,
        ): BordersPaddings {
            if (sampled.isEmpty) return NONE
            if (sampleSize <= 1) return sampled
            val left = (sampled.left * sampleSize).coerceIn(0, fullWidth)
            val top = (sampled.top * sampleSize).coerceIn(0, fullHeight)
            val contentRight = ((sampledWidth - sampled.right) * sampleSize).coerceIn(0, fullWidth)
            val contentBottom = ((sampledHeight - sampled.bottom) * sampleSize).coerceIn(0, fullHeight)
            val right = (fullWidth - contentRight).coerceAtLeast(0)
            val bottom = (fullHeight - contentBottom).coerceAtLeast(0)
            return if (left + right >= fullWidth || top + bottom >= fullHeight) NONE
            else BordersPaddings(left, top, right, bottom)
        }

        /**
         * Smallest power-of-two `inSampleSize` that keeps a `width x height` scan at or below
         * [maxPixels]. Border detection only needs a coarse picture of the page.
         */
        fun sampleSizeFor(width: Int, height: Int, maxPixels: Int = DETECTION_MAX_PIXELS): Int {
            if (width <= 0 || height <= 0) return 1
            var sample = 1
            while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
                sample *= 2
            }
            return sample
        }

        const val DETECTION_MAX_PIXELS = 1_500_000
    }
}
