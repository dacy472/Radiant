package com.radiant.webtoon.core.image.crop

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Per-image-key cache of detected [BordersPaddings] so re-decoding the same page (memory cache
 * eviction, viewer swap, rotation) does not rescan pixels.
 */
object BordersPaddingsCache {
    private const val MAX_ENTRIES = 512

    private val map: MutableMap<String, BordersPaddings> = Collections.synchronizedMap(
        object : LinkedHashMap<String, BordersPaddings>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BordersPaddings>?): Boolean =
                size > MAX_ENTRIES
        },
    )

    fun get(key: String): BordersPaddings? = map[key]

    fun put(key: String, value: BordersPaddings) {
        map[key] = value
    }

    inline fun getOrCompute(key: String?, compute: () -> BordersPaddings): BordersPaddings {
        if (key == null) return compute()
        get(key)?.let { return it }
        return compute().also { put(key, it) }
    }

    fun clear() = map.clear()
}

/**
 * Coil [Decoder] that strips solid-colour borders **while decoding**, on the Coil decode dispatcher
 * (never on the main thread, never via UI offsets/clipping/padding).
 *
 * It delegates the actual decode to Coil's [BitmapFactoryDecoder] (so request size, precision,
 * EXIF handling and parallelism limits still apply), runs [BorderDetector] on the decoded pixels
 * and returns a *cropped bitmap*. The returned drawable therefore reports the cropped intrinsic
 * size, which is what lets the reader lay pages out with exact heights.
 *
 * Enable per request with [cropBorders]. Requests without the parameter fall through to the
 * default decoders untouched, and the parameter's memory-cache key keeps cropped and uncropped
 * bitmaps of the same URL apart in Coil's memory cache.
 */
class CropBordersDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val imageKey: String?,
    private val detectionParams: BorderDetector.Params,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val delegate = BitmapFactoryDecoder(source, options)
        val result = delegate.decode()
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return result
        val cropped = withContext(Dispatchers.Default) { crop(bitmap) } ?: return result
        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, cropped),
            isSampled = result.isSampled,
        )
    }

    private fun crop(bitmap: Bitmap): Bitmap? {
        val readable = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        val w = readable.width
        val h = readable.height
        val cacheKey = imageKey?.let { "$it#${w}x$h" }
        val borders = BordersPaddingsCache.getOrCompute(cacheKey) { detect(readable, w, h) }
        if (borders.isEmpty) return null
        val cw = borders.croppedWidth(w)
        val ch = borders.croppedHeight(h)
        if (cw <= 0 || ch <= 0) return null
        return Bitmap.createBitmap(readable, borders.left, borders.top, cw, ch)
    }

    private fun detect(bitmap: Bitmap, w: Int, h: Int): BordersPaddings {
        val sample = BordersPaddings.sampleSizeFor(w, h)
        val scan: Bitmap = if (sample > 1) {
            Bitmap.createScaledBitmap(bitmap, (w / sample).coerceAtLeast(1), (h / sample).coerceAtLeast(1), false)
        } else {
            bitmap
        }
        val sw = scan.width
        val sh = scan.height
        val pixels = IntArray(sw * sh)
        scan.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (scan !== bitmap) scan.recycle()
        val sampled = BorderDetector.detect(pixels, sw, sh, detectionParams)
        return BordersPaddings.fromSampled(sampled, sample, sw, sh, w, h)
    }

    class Factory(
        private val detectionParams: BorderDetector.Params = BorderDetector.Params(),
    ) : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (options.parameters.value<Boolean>(PARAM_CROP_BORDERS) != true) return null
            val mime = result.mimeType
            if (mime != null && (mime == "image/gif" || mime == "image/svg+xml")) return null
            val key = options.diskCacheKey ?: result.source.fileOrNull()?.let { path ->
                val file = path.toFile()
                "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            }
            return CropBordersDecoder(result.source, options, key, detectionParams)
        }
    }

    companion object {
        const val PARAM_CROP_BORDERS = "radiant.crop_borders"
    }
}

/**
 * Routes the user-facing "Crop borders" setting into the Coil decode chain: when [enabled], the
 * request is decoded by [CropBordersDecoder].
 */
fun ImageRequest.Builder.cropBorders(enabled: Boolean): ImageRequest.Builder = apply {
    if (enabled) {
        // allowHardware(false): the scan needs readable pixels. The memory-cache key segment keeps
        // cropped and uncropped decodes of the same URL separate.
        allowHardware(false)
        setParameter(CropBordersDecoder.PARAM_CROP_BORDERS, true, memoryCacheKey = "crop1")
    }
}
