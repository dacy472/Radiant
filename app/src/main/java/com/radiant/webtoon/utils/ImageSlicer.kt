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
        try {
            val decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(inputStream)
            } else {
                BitmapRegionDecoder.newInstance(inputStream, false)
            }
            
            if (decoder == null) {
                // Fallback for unsupported formats
                val fallback = BitmapFactory.decodeStream(inputStream)
                return@withContext if (fallback != null) listOf(fallback) else emptyList()
            }

            val width = decoder.width
            val height = decoder.height
            val slices = mutableListOf<Bitmap>()
            val options = BitmapFactory.Options()

            if (height <= MAX_TEXTURE_SIZE) {
                val region = Rect(0, 0, width, height)
                decoder.decodeRegion(region, options)?.let { slices.add(it) }
            } else {
                var currentTop = 0
                while (currentTop < height) {
                    val currentBottom = minOf(currentTop + MAX_TEXTURE_SIZE, height)
                    val rect = Rect(0, currentTop, width, currentBottom)
                    decoder.decodeRegion(rect, options)?.let { chunk ->
                        slices.add(chunk)
                    }
                    currentTop += MAX_TEXTURE_SIZE
                }
            }
            decoder.recycle()
            slices
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
