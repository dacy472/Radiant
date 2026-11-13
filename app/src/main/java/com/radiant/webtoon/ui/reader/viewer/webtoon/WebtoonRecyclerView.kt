/*
 * Structure adapted from Mihon's `WebtoonRecyclerView` / `WebtoonFrame`
 * (https://github.com/mihonapp/mihon), Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer.webtoon

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.radiant.webtoon.ui.reader.viewer.GestureDetectorWithLongTap

/**
 * Vertical strip list with pinch zoom, double-tap zoom, horizontal pan while zoomed and the normal
 * RecyclerView fling. Zoom is a view transform of the whole list around its centre; it never
 * changes the layout, so page heights/positions are unaffected by zooming.
 *
 * Pages never consume touches, so taps/drags reach [onTouchEvent] here.
 */
class WebtoonRecyclerView(context: Context) : RecyclerView(context) {

    /** Single tap (confirmed, not a double tap). Coordinates are raw screen coordinates. */
    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    var currentScale = MIN_SCALE
        private set

    /** Set by [WebtoonFrame] while a two-finger pinch is in progress. */
    internal var isPinching = false

    private var lastRawX = 0f
    private var zoomAnimator: ValueAnimator? = null

    private val detector = GestureDetectorWithLongTap(
        context,
        object : GestureDetectorWithLongTap.Listener() {
            override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
                tapListener?.invoke(ev)
                return false
            }

            override fun onDoubleTap(ev: MotionEvent): Boolean {
                toggleZoom()
                return true
            }

            override fun onLongTapConfirmed(ev: MotionEvent) {
                if (longTapListener?.invoke(ev) == true) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
        },
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        if (isPinching) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> lastRawX = e.rawX
            MotionEvent.ACTION_MOVE -> if (currentScale > MIN_SCALE && e.pointerCount == 1) {
                val dx = e.rawX - lastRawX
                lastRawX = e.rawX
                translationX = clampX(translationX + dx)
            }
        }
        return super.onTouchEvent(e)
    }

    /** Multiplies the current zoom by [factor] (pinch). */
    internal fun zoomBy(factor: Float) {
        zoomAnimator?.cancel()
        setScale(currentScale * factor)
    }

    fun resetZoom(animate: Boolean = false) {
        if (currentScale == MIN_SCALE && translationX == 0f) return
        if (animate) animateZoom(MIN_SCALE) else {
            zoomAnimator?.cancel()
            setScale(MIN_SCALE)
            translationX = 0f
        }
    }

    private fun toggleZoom() {
        animateZoom(if (currentScale > MIN_SCALE + 0.01f) MIN_SCALE else DOUBLE_TAP_SCALE)
    }

    private fun animateZoom(target: Float) {
        zoomAnimator?.cancel()
        val startScale = currentScale
        val startX = translationX
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                setScale(startScale + (target - startScale) * t)
                if (target == MIN_SCALE) translationX = startX * (1f - t)
            }
            start()
        }
    }

    private fun setScale(rate: Float) {
        currentScale = rate.coerceIn(MIN_SCALE, MAX_SCALE)
        scaleX = currentScale
        scaleY = currentScale
        translationX = clampX(translationX)
    }

    private fun clampX(x: Float): Float {
        if (currentScale <= MIN_SCALE) return 0f
        val max = width / 2f * (currentScale - 1f)
        return x.coerceIn(-max, max)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val ZOOM_DURATION_MS = 250L
    }
}

/**
 * Hosts the [WebtoonRecyclerView]. Pinch gestures are detected here, in *unscaled* coordinates,
 * so zooming does not feed back into the detector (a scaled child would distort the focal span).
 */
class WebtoonFrame(context: Context) : FrameLayout(context) {

    private val recycler: WebtoonRecyclerView?
        get() = getChildAt(0) as? WebtoonRecyclerView

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                recycler?.isPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                recycler?.zoomBy(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                recycler?.isPinching = false
            }
        },
    ).apply { isQuickScaleEnabled = false }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            recycler?.isPinching = false
        }
        return super.dispatchTouchEvent(ev)
    }
}
