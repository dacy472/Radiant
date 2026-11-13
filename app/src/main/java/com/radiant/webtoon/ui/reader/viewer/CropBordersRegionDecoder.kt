package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import com.radiant.webtoon.core.image.crop.BorderDetector
import com.radiant.webtoon.core.image.crop.BordersPaddings
import com.radiant.webtoon.core.image.crop.BordersPaddingsCache
import java.io.File

/**
 * SubsamplingScaleImageView hook for Crop Borders — the tiled-decoder twin of
 * [com.radiant.webtoon.core.image.crop.CropBordersDecoder].
 *
 * It wraps SSIV's stock [SkiaImageRegionDecoder] and runs entirely on SSIV's decode executor:
 *  1. `init` opens the image, reads its real size, and (if [cropBorders]) decodes one *coarse
 *     sampled* copy via the same region decoder, scans it with [BorderDetector] and caches the
 *     resulting [BordersPaddings] per image key.
 *  2. It reports the **cropped** size to SSIV, so the view's intrinsic size, wrap-content height,
 *     scale and tiling all use the cropped dimensions (no overlap in the webtoon strip).
 *  3. `decodeRegion` shifts every requested tile by the left/top padding, so only artwork pixels
 *     are ever decoded — no UI offsets, margins, padding or clipping are involved.
 */
class CropBordersRegionDecoder(
    private val cropBorders: Boolean,
    private val onSizeResolved: ((Point) -> Unit)? = null,
) : ImageRegionDecoder {

    private val delegate = SkiaImageRegionDecoder(Bitmap.Config.ARGB_8888)

    @Volatile
    private var borders: BordersPaddings = BordersPaddings.NONE

    override fun init(context: Context, uri: Uri): Point {
        val fullSize = delegate.init(context, uri)
        borders = if (cropBorders) {
            BordersPaddingsCache.getOrCompute(cacheKeyFor(uri, fullSize)) { detect(fullSize) }
        } else {
            BordersPaddings.NONE
        }
        val b = borders
        val size = Point(b.croppedWidth(fullSize.x), b.croppedHeight(fullSize.y))
        onSizeResolved?.invoke(size)
        return size
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val b = borders
        if (b.isEmpty) return delegate.decodeRegion(sRect, sampleSize)
        val shifted = Rect(sRect.left + b.left, sRect.top + b.top, sRect.right + b.left, sRect.bottom + b.top)
        return delegate.decodeRegion(shifted, sampleSize)
    }

    override fun isReady(): Boolean = delegate.isReady

    override fun recycle() = delegate.recycle()

    private fun detect(fullSize: Point): BordersPaddings = try {
        val w = fullSize.x
        val h = fullSize.y
        val sample = BordersPaddings.sampleSizeFor(w, h)
        val bitmap = delegate.decodeRegion(Rect(0, 0, w, h), sample)
        val sw = bitmap.width
        val sh = bitmap.height
        val pixels = IntArray(sw * sh)
        bitmap.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        bitmap.recycle()
        val sampled = BorderDetector.detect(pixels, sw, sh)
        BordersPaddings.fromSampled(sampled, sample, sw, sh, w, h)
    } catch (_: Throwable) {
        // Never fail the page because border detection failed: just show it uncropped.
        BordersPaddings.NONE
    }

    private fun cacheKeyFor(uri: Uri, size: Point): String? {
        val raw = uri.toString()
        val path = if (raw.startsWith("file://")) raw.removePrefix("file://") else null
        val identity = if (path != null) {
            val file = File(path)
            "$path:${file.length()}:${file.lastModified()}"
        } else {
            raw
        }
        return "$identity#${size.x}x${size.y}"
    }
}
