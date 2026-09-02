package com.radiant.webtoon.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object ImageSlicer {
    private const val MAX_TEXTURE_SIZE = 2048

    @Suppress("DEPRECATION")
    suspend fun slice(inputStream: InputStream): List<Bitmap> = withContext(Dispatchers.IO) {
        sliceWithMetadata(inputStream).map { it.bitmap }
    }

  /**
     * Splits a tall page image into GPU-safe vertical tiles.
     *
     * Slices use half-open source ranges `[top, bottom)` so consecutive tiles align
     * pixel-perfectly and no rows are skipped or duplicated at boundaries.
     */
    @Suppress("DEPRECATION")
    suspend fun sliceWithMetadata(inputStream: InputStream): List<ImageSlice> = withContext(Dispatchers.IO) {
        var decoder: BitmapRegionDecoder? = null
        try {
            decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(inputStream)
            } else {
                BitmapRegionDecoder.newInstance(inputStream, false)
            }

            if (decoder == null) {
                val fallback = BitmapFactory.decodeStream(inputStream)
                return@withContext if (fallback != null) {
                    listOf(
                        ImageSlice(
                            bitmap = fallback,
                            sourceTop = 0,
                            sourceBottom = fallback.height,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            val width = decoder.width
            val height = decoder.height
            val slices = mutableListOf<ImageSlice>()
            val options = BitmapFactory.Options()

            if (height <= MAX_TEXTURE_SIZE) {
                val region = Rect(0, 0, width, height)
                decoder.decodeRegion(region, options)?.let { bitmap ->
                    slices += ImageSlice(bitmap = bitmap, sourceTop = 0, sourceBottom = height)
                }
            } else {
                var currentTop = 0
                while (currentTop < height) {
                    val currentBottom = minOf(currentTop + MAX_TEXTURE_SIZE, height)
                    val rect = Rect(0, currentTop, width, currentBottom)
                    decoder.decodeRegion(rect, options)?.let { bitmap ->
                        slices += ImageSlice(
                            bitmap = bitmap,
                            sourceTop = currentTop,
                            sourceBottom = currentBottom,
                        )
                    }
                    currentTop = currentBottom
                }
            }

            if (!ImageSliceCoverage.isComplete(height, slices)) {
                slices.forEach { it.bitmap.recycle() }
                emptyList()
            } else {
                slices
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            decoder?.recycle()
        }
    }
}
