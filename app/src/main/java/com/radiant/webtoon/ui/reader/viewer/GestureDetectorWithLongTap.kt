/*
 * Adapted from Mihon's `GestureDetectorWithLongTap` (https://github.com/mihonapp/mihon),
 * Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A gesture detector that also reports a confirmed long tap; the built-in one conflicts with the
 * quick-scale gesture.
 */
open class GestureDetectorWithLongTap(
    context: Context,
    private val tapListener: Listener,
) : GestureDetector(context, tapListener) {

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastUp = 0L
    private var lastDownEvent: MotionEvent? = null

    private val longTapFn = Runnable { lastDownEvent?.let { tapListener.onLongTapConfirmed(it) } }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownEvent?.recycle()
                lastDownEvent = MotionEvent.obtain(ev)

                // Ignore the event if the last up and this down are close in time (double tap).
                if (ev.downTime - lastUp > doubleTapTime) {
                    downX = ev.x
                    downY = ev.y
                    handler.postDelayed(longTapFn, longTapTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    handler.removeCallbacks(longTapFn)
                }
            }
            MotionEvent.ACTION_UP -> {
                lastUp = ev.eventTime
                handler.removeCallbacks(longTapFn)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longTapFn)
            }
        }
        return super.onTouchEvent(ev)
    }

    open class Listener : SimpleOnGestureListener() {
        /** Notified when a long tap occurs, with the initial down event that triggered it. */
        open fun onLongTapConfirmed(ev: MotionEvent) {}
    }
}
