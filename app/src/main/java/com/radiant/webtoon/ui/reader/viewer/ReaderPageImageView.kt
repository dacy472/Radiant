/*
 * Structure adapted from Mihon's `ReaderPageImageView` (https://github.com/mihonapp/mihon),
 * Apache License 2.0. Rewritten for SubsamplingScaleImageView 3.10 (pluggable decoders) and Coil 2.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.radiant.webtoon.ui.reader.loader.LoadedPage
import com.radiant.webtoon.ui.reader.loader.ReaderPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders one page image.
 *
 *  - **Tiled path** (default): [SubsamplingScaleImageView] backed by [CropBordersRegionDecoder]. It
 *    decodes only the visible tiles, so very tall webtoon strips (taller than the GPU texture
 *    limit) are never downsampled or cut. Crop Borders happens inside the decoder.
 *  - **Coil fallback**: for formats `BitmapRegionDecoder` cannot read (e.g. GIF). The bitmap is
 *    decoded by Coil, optionally through `CropBordersDecoder`.
 *
 * In webtoon mode the view is `match_parent × wrap_content`: SSIV measures its height from the
 * decoded (cropped) aspect ratio at the viewport width, so heights are exact.
 */
class ReaderPageImageView(
    context: Context,
    private val isWebtoon: Boolean,
) : FrameLayout(context) {

    private var pageView: View? = null

    /** Cropped image size once known; drives [hasImageSize] for stable webtoon layout. */
    @Volatile
    private var imageSizeKnown = false

    /** Main thread: the decoder reported the final (cropped) size; layout height is now exact. */
    var onImageSizeKnown: (() -> Unit)? = null
    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((Float) -> Unit)? = null

    private var fallbackJob: Job? = null

    /** `true` as soon as the decoder reported the (cropped) image size. */
    val hasImageSize: Boolean get() = imageSizeKnown

    data class Config(
        val cropBorders: Boolean = false,
        val zoomDuration: Int = 300,
    )

    fun setImage(
        loaded: LoadedPage,
        config: Config,
        scope: CoroutineScope,
        loader: ReaderPageLoader,
    ) {
        imageSizeKnown = false
        val view = prepareTiledView()
        view.setRegionDecoderFactory(
            DecoderFactory<ImageRegionDecoder> {
                CropBordersRegionDecoder(config.cropBorders) {
                    imageSizeKnown = true
                    // Decoder runs off the main thread; SSIV re-measures on its own once the
                    // size is applied, we only need to collapse the progress placeholder.
                    post { onImageSizeKnown?.invoke() }
                }
            },
        )
        view.setDoubleTapZoomDuration(config.zoomDuration)
        view.setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    if (!isWebtoon) setupZoom(view)
                    onImageLoaded?.invoke()
                }

                override fun onImageLoadError(e: Exception) {
                    // Tiled decoder can't read it → fall back to Coil's bitmap decode.
                    useCoilFallback(loaded, config, scope, loader, e)
                }
            },
        )
        view.setImage(ImageSource.uri(loaded.uri).tilingEnabled())
        view.isVisible = true
    }

    fun recycle() {
        fallbackJob?.cancel()
        fallbackJob = null
        imageSizeKnown = false
        when (val v = pageView) {
            is SubsamplingScaleImageView -> v.recycle()
            is ImageView -> v.setImageDrawable(null)
        }
        pageView?.isVisible = false
    }

    // ── Pan helpers (pager "navigate to pan") ─────────────────────────────────────────

    fun canPanLeft(): Boolean = canPan { it.left }
    fun canPanRight(): Boolean = canPan { it.right }

    private fun canPan(side: (RectF) -> Float): Boolean {
        val view = pageView as? SubsamplingScaleImageView ?: return false
        if (!view.isReady) return false
        val rect = RectF()
        view.getPanRemaining(rect)
        return side(rect) > 1f
    }

    fun panLeft() = pan { center, view -> center.also { it.x -= view.width / view.scale } }
    fun panRight() = pan { center, view -> center.also { it.x += view.width / view.scale } }

    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        val view = pageView as? SubsamplingScaleImageView ?: return
        val target = fn(view.center ?: return, view)
        view.animateCenter(target)
            ?.withEasing(SubsamplingScaleImageView.EASE_OUT_QUAD)
            ?.withDuration(250)
            ?.withInterruptible(true)
            ?.start()
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────

    private fun prepareTiledView(): SubsamplingScaleImageView {
        (pageView as? SubsamplingScaleImageView)?.let { return it }
        pageView?.let { removeView(it) }

        val view: SubsamplingScaleImageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context).apply {
                // The RecyclerView/WebtoonFrame owns zoom & pan in webtoon mode.
                isZoomEnabled = false
                isPanEnabled = false
                isQuickScaleEnabled = false
                // START → scale = max(viewW / imgW, viewH / imgH) = viewW / imgW because the view's
                // height *is* the fit-to-width height: image fills the width exactly, no side gaps.
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_START)
            }
        } else {
            SubsamplingScaleImageView(context).apply {
                setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            }
        }
        view.setMinimumTileDpi(180)
        view.setMinimumDpi(1) // very small images still fit the view
        view.setOnStateChangedListener(
            object : SubsamplingScaleImageView.OnStateChangedListener {
                override fun onScaleChanged(newScale: Float, origin: Int) {
                    this@ReaderPageImageView.onScaleChanged?.invoke(newScale)
                }

                override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
            },
        )
        pageView = view
        addView(view, MATCH_PARENT, if (isWebtoon) WRAP_CONTENT else MATCH_PARENT)
        return view
    }

    private fun setupZoom(view: SubsamplingScaleImageView) {
        view.maxScale = view.scale * MAX_ZOOM_SCALE
        view.setDoubleTapZoomScale(view.scale * 2)
    }

    private fun useCoilFallback(
        loaded: LoadedPage,
        config: Config,
        scope: CoroutineScope,
        loader: ReaderPageLoader,
        cause: Throwable,
    ) {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            try {
                val widthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val drawable = loader.decodeDrawable(loaded, widthPx, config.cropBorders)
                val imageView = prepareFallbackView()
                imageView.setImageDrawable(drawable)
                if (isWebtoon) {
                    val h = ReaderLayoutMath.pageHeightForWidth(
                        widthPx,
                        drawable.intrinsicWidth,
                        drawable.intrinsicHeight,
                    )
                    val lp = imageView.layoutParams
                    lp.height = if (h > 0) h else WRAP_CONTENT
                    imageView.layoutParams = lp
                }
                imageSizeKnown = true
                imageView.isVisible = true
                onImageSizeKnown?.invoke()
                onImageLoaded?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.addSuppressed(cause)
                onImageLoadError?.invoke(e)
            }
        }
    }

    private fun prepareFallbackView(): ImageView {
        (pageView as? ImageView)?.let { return it }
        pageView?.let { v ->
            (v as? SubsamplingScaleImageView)?.recycle()
            removeView(v)
        }
        val view = AppCompatImageView(context).apply {
            // Webtoon: width fills the page and height is set explicitly from the aspect ratio
            // (FIT_XY), so measured height == drawn height. Paged: classic fit-center.
            scaleType = if (isWebtoon) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
        }
        pageView = view
        addView(view, MATCH_PARENT, if (isWebtoon) WRAP_CONTENT else MATCH_PARENT)
        return view
    }

    private companion object {
        const val MAX_ZOOM_SCALE = 5f
    }
}

/**
 * SSIV used inside the webtoon RecyclerView. It never consumes touches: scrolling, pinch zoom and
 * double-tap zoom are handled by `WebtoonRecyclerView` / `WebtoonFrame`.
 */
internal class WebtoonSubsamplingImageView(context: Context) : SubsamplingScaleImageView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
