package com.radiant.webtoon.ui.reader.loader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import android.util.Log
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.image.ImageFetchRetry
import com.radiant.webtoon.core.image.ImageSourceId
import com.radiant.webtoon.core.source.PageRefs
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import com.radiant.webtoon.core.image.SourceImageHeaders
import com.radiant.webtoon.core.image.crop.CropBordersDecoder
import com.radiant.webtoon.core.image.crop.cropBorders
import com.radiant.webtoon.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A page ready for the viewers: a local image the tiled decoder can open. */
data class LoadedPage(
    /** `file://` (raw, un-encoded path) or `content://` URI. */
    val uri: Uri,
    /** `true` when the page was streamed through Coil into the reader cache. */
    val fromNetwork: Boolean,
)

/**
 * Resolves a [ReaderPage] to a local image for the native viewers.
 *
 * Extension pages are downloaded like Mihon does, via the extension's own client and
 * `imageRequest` ([TachiyomiSourceAdapter.openPageImage]), so per-source headers, interceptors and
 * descramblers apply. Other sources — and plain URLs the extension path failed on — go through the
 * app-wide Coil [ImageLoader] (its OkHttp client = `NetworkHelper.client`, including
 * `SourceImageCoilInterceptor` / `SourceImageOkHttpInterceptor` Referer + User-Agent injection and
 * the Cloudflare cookie jar), where a tiny [PageCaptureDecoder] stores the fetched bytes in
 * `cacheDir/reader_pages`. Both paths write the same cache file so
 * SubsamplingScaleImageView can decode big strips in tiles instead of loading one giant bitmap.
 * Downloaded/offline chapters (`file://`, `content://`) are used in place.
 */
class ReaderPageLoader(
    context: Context,
    private val sourceId: () -> String,
    private val sourceHeaders: () -> Headers,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Deferred<LoadedPage>>()
    private val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)

    /** App Coil loader + reader-only decoder factories (shares caches and the OkHttp client). */
    private val imageLoader: ImageLoader by lazy {
        appContext.imageLoader.newBuilder()
            .components {
                add(PageCaptureDecoder.Factory())
                add(CropBordersDecoder.Factory())
            }
            .build()
    }

    suspend fun load(page: ReaderPage): LoadedPage {
        val deferred = jobs.computeIfAbsent(page.url) { scope.async { loadInternal(page) } }
        try {
            return deferred.await()
        } catch (e: Throwable) {
            // Forget failures so "Retry" starts a fresh request instead of replaying the error.
            if (deferred.isCompleted) jobs.remove(page.url, deferred)
            throw e
        }
    }

    fun forget(page: ReaderPage) {
        jobs.remove(page.url)
    }

    /**
     * Decodes [loaded] with Coil (used when the tiled decoder cannot read the format, e.g. GIF).
     * Crop Borders runs in [CropBordersDecoder] when [cropBorders] is true.
     */
    suspend fun decodeDrawable(loaded: LoadedPage, widthPx: Int, cropBorders: Boolean): Drawable {
        val request = ImageRequest.Builder(appContext)
            .data(loaded.uri)
            // Height-capped FIT keeps tall strips under Android's bitmap/canvas size limit.
            .size(Dimension.Pixels(widthPx.coerceAtLeast(1)), Dimension.Pixels(MAX_FALLBACK_HEIGHT_PX))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .cropBorders(cropBorders)
            .build()
        return when (val result = imageLoader.execute(request)) {
            is ErrorResult -> throw result.throwable
            else -> result.drawable ?: throw IllegalStateException("Image decode returned no drawable")
        }
    }

    fun close() {
        scope.cancel()
        jobs.clear()
    }

    private suspend fun loadInternal(page: ReaderPage): LoadedPage {
        if (page.isLocal) return LoadedPage(rawUri(page.url), fromNetwork = false)

        val target = File(cacheDir, sha1(page.url))
        if (target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return LoadedPage(rawUri("file://${target.absolutePath}"), fromNetwork = true)
        }
        withContext(Dispatchers.IO) { cacheDir.mkdirs() }

        val id = sourceId()
        val source = (appContext as? WebtoonApplication)?.extensionManager?.getSource(id)
        val adapter = source as? TachiyomiSourceAdapter
        if (adapter != null) {
            val viaExtension = runCatching {
                ImageFetchRetry.run { fetchViaExtension(adapter, page.url, target) }
            }
            viaExtension.exceptionOrNull()?.let { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Deferred refs have no plain URL for Coil to try.
                if (PageRefs.isDeferred(page.url)) throw error
                Log.w(TAG, "Extension image fetch failed, retrying via Coil: ${error.message}")
            }
            if (viaExtension.getOrNull() == true) {
                return LoadedPage(rawUri("file://${target.absolutePath}"), fromNetwork = true)
            }
        } else if (PageRefs.isDeferred(page.url)) {
            throw IllegalStateException("Source $id cannot resolve this page")
        }
        val coilAttempts = if (adapter != null) 1 else ImageFetchRetry.MAX_ATTEMPTS
        ImageFetchRetry.run(coilAttempts) { fetchViaCoil(page, id, source, target) }
        return LoadedPage(rawUri("file://${target.absolutePath}"), fromNetwork = true)
    }

    /**
     * Mihon parity: `source.getImage(page)` with the extension's own client and `imageRequest`.
     * Returns false when the wrapped source is not an HttpSource.
     */
    private suspend fun fetchViaExtension(
        adapter: TachiyomiSourceAdapter,
        ref: String,
        target: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val response = adapter.openPageImage(ref) ?: return@withContext false
        val tmp = File(target.parentFile, target.name + ".part")
        response.use { tmp.sink().buffer().use { sink -> sink.writeAll(it.body.source()) } }
        if (tmp.length() <= 0L) {
            tmp.delete()
            throw java.io.IOException("Empty image body")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }

    private suspend fun fetchViaCoil(
        page: ReaderPage,
        id: String,
        source: com.radiant.webtoon.core.source.Source?,
        target: File,
    ) {
        val resolvedHeaders = SourceImageHeaders.resolve(
            explicit = sourceHeaders(),
            source = source,
            defaultUserAgent = SourceImageHeaders.defaultNetworkUserAgent(),
        )
        val request = ImageRequest.Builder(appContext)
            .data(page.url)
            .headers(resolvedHeaders)
            .apply {
                if (id.isNotBlank()) {
                    tag(ImageSourceId::class.java, ImageSourceId(id))
                    addHeader(SourceImageHeaders.SOURCE_ID_HEADER, id)
                }
            }
            .setParameter(PageCaptureDecoder.PARAM_TARGET, target.absolutePath, memoryCacheKey = null)
            .memoryCachePolicy(CachePolicy.DISABLED)
            // READ_ONLY: reuse covers/pages already in Coil's disk cache, but keep a single copy
            // (ours) for newly fetched pages.
            .diskCachePolicy(CachePolicy.READ_ONLY)
            .crossfade(false)
            .build()

        when (val result = imageLoader.execute(request)) {
            is ErrorResult -> throw result.throwable
            else -> Unit
        }
        if (target.length() <= 0L) throw java.io.IOException("Page was not stored: ${page.url}")
    }

    /** Deletes the oldest reader-cache files above [maxBytes]. */
    fun trimCache(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        private const val TAG = "ReaderPageLoader"
        private const val CACHE_DIR_NAME = "reader_pages"
        private const val MAX_CACHE_BYTES = 150L * 1024 * 1024
        private const val MAX_FALLBACK_HEIGHT_PX = 16_384

        /**
         * `Uri.parse` keeps the string un-encoded (unlike `Uri.fromFile`), which is what
         * SubsamplingScaleImageView's file handling expects.
         */
        fun rawUri(url: String): Uri = Uri.parse(url)

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Coil decoder that doesn't decode: it copies the fetched bytes to the file named by
 * [PARAM_TARGET] so the tiled viewer can open them later. Only active for requests carrying that
 * parameter.
 */
internal class PageCaptureDecoder(
    private val source: ImageSource,
    private val target: File,
) : Decoder {

    override suspend fun decode(): DecodeResult = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.sink().buffer().use { sink -> sink.writeAll(source.source()) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        DecodeResult(ColorDrawable(Color.TRANSPARENT), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val path = options.parameters.value<String>(PARAM_TARGET) ?: return null
            return PageCaptureDecoder(result.source, File(path))
        }
    }

    companion object {
        const val PARAM_TARGET = "radiant.reader.capture_target"
    }
}
