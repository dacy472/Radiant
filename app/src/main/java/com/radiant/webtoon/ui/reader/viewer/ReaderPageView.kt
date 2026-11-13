/*
 * Structure adapted from Mihon's `ReaderProgressIndicator` / `ReaderErrorBinding` page views
 * (https://github.com/mihonapp/mihon), Apache License 2.0.
 */
package com.radiant.webtoon.ui.reader.viewer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.radiant.webtoon.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.network.CloudflareException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One page of the reader: image + progress + error, with exactly one of them occupying layout
 * space at a time.
 *
 * Webtoon (`isWebtoon`): the view is `match_parent × wrap_content`.
 *  - LOADING / ERROR: a status container of `placeholderHeight` is the only sized child (the image
 *    view is collapsed), so the placeholder never overlaps its neighbours.
 *  - READY: as soon as the decoder reports the (cropped) size the status containers are `GONE` and
 *    the image's exact `width × width·h/w` height is the only thing left → no overlap, no clipping,
 *    no gaps, regardless of the order pages finish loading.
 */
class ReaderPageView(
    context: Context,
    private val host: ReaderViewerHost,
    private val isWebtoon: Boolean,
) : FrameLayout(context) {

    enum class State { QUEUE, LOADING, READY, ERROR }

    var page: ReaderPage? = null
        private set
    var state: State = State.QUEUE
        private set

    /** Height of the progress / error placeholder in webtoon mode (see [ReaderLayoutMath]). */
    var placeholderHeightPx: Int = resources.displayMetrics.heightPixels / 2
        set(value) {
            if (field == value) return
            field = value
            applyStatusHeight()
        }

    val imageView = ReaderPageImageView(context, isWebtoon)

    private val progressContainer = FrameLayout(context).apply {
        addView(
            ProgressBar(context).apply { isIndeterminate = true },
            LayoutParams(dp(48), dp(48), Gravity.CENTER),
        )
    }

    private val errorMessage = TextView(context).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textSize = 14f
    }
    private val retryButton = Button(context).apply { text = "Retry" }
    private val cloudflareButton = Button(context).apply {
        text = "Solve challenge in WebView"
        isVisible = false
    }
    private val errorContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        isVisible = false
        addView(errorMessage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(
            cloudflareButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) },
        )
        addView(
            retryButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
    }

    private var loadJob: Job? = null
    private var lastError: Throwable? = null

    /** Invoked after the final image height is laid out (webtoon resume uses it). */
    var onFinalHeightLaidOut: ((ReaderPageView) -> Unit)? = null

    init {
        val h = if (isWebtoon) WRAP_CONTENT else MATCH_PARENT
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, h)
        addView(imageView, LayoutParams(MATCH_PARENT, h))
        addView(progressContainer, LayoutParams(MATCH_PARENT, statusHeight()))
        addView(errorContainer, LayoutParams(MATCH_PARENT, statusHeight()))

        retryButton.setOnClickListener { retry() }
        cloudflareButton.setOnClickListener {
            val p = page ?: return@setOnClickListener
            val cf = lastError?.let { CloudflareException.unwrap(it) }
            val pageUrl = com.radiant.webtoon.core.source.PageRefs.parseDeferred(p.url)?.pageUrl ?: p.url
            host.solveCloudflareChallenge(cf?.url ?: pageUrl, host.sourceReferer)
        }

        imageView.onImageSizeKnown = {
            // Final (cropped) height is known: the image alone defines this page's height.
            progressContainer.isVisible = false
            errorContainer.isVisible = false
            requestLayout()
            onFinalHeightLaidOut?.let { callback -> doOnNextLayout { callback(this) } }
        }
        imageView.onImageLoaded = {
            state = State.READY
            progressContainer.isVisible = false
            errorContainer.isVisible = false
        }
        imageView.onImageLoadError = { e -> showError(e) }
    }

    fun bind(page: ReaderPage) {
        if (this.page == page && state != State.ERROR) return
        recycle()
        this.page = page
        start()
    }

    /** Re-run after returning from the WebView challenge. */
    fun retry() {
        val p = page ?: return
        host.pageLoader.forget(p)
        recycle()
        page = p
        start()
    }

    fun retryIfFailed() {
        if (state == State.ERROR) retry()
    }

    fun recycle() {
        loadJob?.cancel()
        loadJob = null
        imageView.recycle()
        state = State.QUEUE
        page = null
        lastError = null
        progressContainer.isVisible = false
        errorContainer.isVisible = false
        applyStatusHeight()
    }

    private fun start() {
        val p = page ?: return
        state = State.LOADING
        lastError = null
        showStatus(progress = true)
        loadJob = host.scope.launch {
            try {
                val loaded = host.pageLoader.load(p)
                if (page != p) return@launch
                val settings = host.viewerSettings
                imageView.setImage(
                    loaded = loaded,
                    config = ReaderPageImageView.Config(cropBorders = settings.cropBorders),
                    scope = host.scope,
                    loader = host.pageLoader,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (page == p) showError(e)
            }
        }
    }

    private fun showError(e: Throwable?) {
        state = State.ERROR
        lastError = e
        val cf = e?.let { CloudflareException.unwrap(it) }
        val msg = e?.message.orEmpty()
        val looksBlocked = cf != null || msg.contains("403") || msg.contains("503")
        errorMessage.text = when {
            cf != null -> "Cloudflare protection blocked this page."
            msg.isNotBlank() -> "Couldn't load page ${page?.number ?: ""}\n$msg"
            else -> "Couldn't load page ${page?.number ?: ""}"
        }
        cloudflareButton.isVisible = looksBlocked && page?.isLocal == false
        showStatus(progress = false)
    }

    private fun showStatus(progress: Boolean) {
        progressContainer.isVisible = progress
        errorContainer.isVisible = !progress
        applyStatusHeight()
    }

    private fun statusHeight(): Int = if (isWebtoon) placeholderHeightPx else MATCH_PARENT

    private fun applyStatusHeight() {
        val h = statusHeight()
        for (v in arrayOf<View>(progressContainer, errorContainer)) {
            val lp = v.layoutParams
            if (lp != null && lp.height != h) {
                lp.height = h
                v.layoutParams = lp
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
