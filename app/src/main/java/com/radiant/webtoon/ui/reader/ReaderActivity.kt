package com.radiant.webtoon.ui.reader

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.ui.reader.loader.ReaderPageLoader
import com.radiant.webtoon.ui.reader.model.ReaderChapterInfo
import com.radiant.webtoon.ui.reader.model.ReaderPage
import com.radiant.webtoon.ui.reader.model.ViewerChapters
import com.radiant.webtoon.ui.reader.viewer.ReaderViewerHost
import com.radiant.webtoon.ui.reader.viewer.ReadingMode
import com.radiant.webtoon.ui.reader.viewer.Viewer
import com.radiant.webtoon.ui.reader.viewer.ViewerSettings
import com.radiant.webtoon.ui.reader.viewer.pager.L2RPagerViewer
import com.radiant.webtoon.ui.reader.viewer.pager.R2LPagerViewer
import com.radiant.webtoon.ui.reader.viewer.pager.VerticalPagerViewer
import com.radiant.webtoon.ui.reader.viewer.webtoon.ContinuousVerticalViewer
import com.radiant.webtoon.ui.reader.viewer.webtoon.WebtoonViewer
import com.radiant.webtoon.ui.theme.WebtoonTheme
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * View-based chapter reader. History, series detail, and updates still navigate to the `reader/…`
 * route; that route starts this activity instead of the Compose continuous reader.
 *
 * Progress, adjacent chapters, downloads/offline pages, and the inline WebView Cloudflare recovery
 * stay on [ReaderViewModel] and the existing bypass activity. Page images are loaded by
 * [ReaderPageLoader] through the app Coil/OkHttp client.
 */
class ReaderActivity : AppCompatActivity(), ReaderViewerHost {

    override val context: Context get() = this
    override val scope get() = lifecycleScope
    override val pageLoader: ReaderPageLoader get() = loader
    override val viewerSettings: ViewerSettings get() = currentSettings
    override val isMenuVisible: Boolean get() = menuVisible
    override val sourceReferer: String?
        get() {
            val id = viewModel.currentSeriesSourceId
            if (id.isBlank()) return null
            return app.extensionManager.getSource(id)?.baseUrl
        }

    private val app by lazy { application as WebtoonApplication }
    private lateinit var viewModel: ReaderViewModel
    private lateinit var loader: ReaderPageLoader

    private lateinit var viewerContainer: FrameLayout
    private lateinit var statusOverlay: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusMessage: TextView
    private lateinit var statusPrimary: Button
    private lateinit var statusSecondary: Button
    private lateinit var menuRoot: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pageSeek: SeekBar
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var cropButton: Button
    private lateinit var modeButton: Button
    private lateinit var settingsCompose: ComposeView

    private var viewer: Viewer? = null
    private var viewerMode: ReadingMode? = null
    private var viewerCrop: Boolean? = null
    private var currentSettings = ViewerSettings()
    private var menuVisible = false
    private var seeking = false
    private var awaitingChallenge = false
    private var boundToken: String? = null
    private var boundGeneration = -1L

    private val showSettings = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        val webtoonId = intent.getStringExtra(EXTRA_WEBTOON_ID).orEmpty()
        val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID).orEmpty()
        val chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty().ifBlank { "Chapter" }
        if (sourceId.isBlank() || webtoonId.isBlank() || chapterId.isBlank()) {
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            ReaderViewModelFactory(application, app.repository),
        )[ReaderViewModel::class.java]
        buildContent()
        loader = ReaderPageLoader(
            context = this,
            sourceId = { viewModel.currentSeriesSourceId },
            sourceHeaders = { viewModel.sourceHeaders.value },
        )
        lifecycleScope.launch(Dispatchers.IO) { loader.trimCache() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        showSettings.value -> dismissSettings()
                        menuVisible -> setMenuVisible(false)
                        else -> {
                            isEnabled = false
                            viewModel.flushLastPage()
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )

        observePreferences()
        observeReader()
        viewModel.loadChapter(sourceId, webtoonId, chapterId, chapterTitle)
        setMenuVisible(false)
    }

    override fun onPause() {
        if (::viewModel.isInitialized) viewModel.flushLastPage()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingChallenge || !::viewModel.isInitialized) return
        awaitingChallenge = false
        viewer?.retryFailedPages()
        if (viewModel.pageError.value is ReaderPageError.CloudflareBlocked) {
            reloadCurrentChapter()
        }
    }

    override fun onDestroy() {
        viewer?.destroy()
        viewer = null
        if (::loader.isInitialized) loader.close()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewer?.handleKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (viewer?.handleGenericMotionEvent(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onPageSelected(page: ReaderPage) {
        val generation = viewModel.loadGeneration.value
        // Continuous viewers persist through onReadPositionChanged (top page + in-page offset).
        viewModel.onPageSelected(page.index, generation, persist = viewer !is WebtoonViewer)
        val count = viewModel.pages.value.size
        if (!seeking && count > 0) {
            pageSeek.max = (count - 1).coerceAtLeast(0)
            pageSeek.progress = page.index.coerceIn(0, pageSeek.max)
            pageLabel.text = "${page.number} / $count"
        }
    }

    override fun onReadPositionChanged(page: ReaderPage, offsetPermyriad: Int) {
        viewModel.onReadPositionChanged(page.index, offsetPermyriad, viewModel.loadGeneration.value)
    }

    override fun toggleMenu() = setMenuVisible(!menuVisible)
    override fun showMenu() = setMenuVisible(true)
    override fun hideMenu() = setMenuVisible(false)
    override fun loadNextChapter() = viewModel.loadNextChapter()
    override fun loadPreviousChapter() = viewModel.loadPreviousChapter()

    override fun solveCloudflareChallenge(url: String?, referer: String?) {
        val target = url?.takeIf { it.isNotBlank() }
            ?: (viewModel.pageError.value as? ReaderPageError.CloudflareBlocked)?.chapterUrl
            ?: return
        awaitingChallenge = true
        CloudflareInterceptor.launchVisibleBypass(
            context = this,
            url = target,
            referer = referer ?: sourceReferer,
            showToast = true,
        )
    }

    private fun reloadCurrentChapter() {
        val sourceId = viewModel.currentSeriesSourceId
        val webtoonId = viewModel.currentSeriesWebtoonId
        val chapterId = viewModel.currentChapterId
        if (sourceId.isBlank() || webtoonId.isBlank() || chapterId.isBlank()) return
        viewModel.loadChapter(sourceId, webtoonId, chapterId, viewModel.activeChapterTitle.value)
    }

    private fun observeReader() {
        lifecycleScope.launch {
            combine(
                combine(
                    viewModel.pages,
                    viewModel.loadGeneration,
                    viewModel.initialPageIndex,
                    viewModel.readingMode,
                    viewModel.cropBorders,
                ) { pages, generation, initialPage, mode, crop ->
                    ReaderBind(pages, generation, initialPage, mode, crop)
                },
                viewModel.isLoading,
            ) { bind, loading -> bind to loading }
                .collect { (bind, loading) ->
                    // Wait until loadChapter has stored the resume page. Binding earlier opens page 0.
                    if (loading) {
                        renderStatus()
                    } else {
                        renderChapter(bind)
                        renderStatus()
                    }
                }
        }
        lifecycleScope.launch {
            combine(viewModel.isLoading, viewModel.pageError, viewModel.pageListDebugMode) { _, _, _ -> Unit }
                .collect { renderStatus() }
        }
        lifecycleScope.launch {
            viewModel.activeChapterTitle.collect { titleView.text = it.ifBlank { "Chapter" } }
        }
        lifecycleScope.launch {
            combine(viewModel.hasPreviousChapter, viewModel.hasNextChapter) { prev, next -> prev to next }
                .collect { (prev, next) ->
                    prevButton.isEnabled = prev
                    nextButton.isEnabled = next
                }
        }
        lifecycleScope.launch {
            viewModel.cropBorders.collect { on -> cropButton.text = if (on) "Crop on" else "Crop off" }
        }
        lifecycleScope.launch {
            viewModel.readingMode.collect { modeButton.text = it.label }
        }
        lifecycleScope.launch {
            viewModel.backgroundColor.collect { color ->
                val argb = color.toArgb()
                viewerContainer.setBackgroundColor(argb)
                currentSettings = currentSettings.copy(
                    contentColor = if (isLight(argb)) Color.BLACK else Color.WHITE,
                )
            }
        }
    }

    private fun renderChapter(bind: ReaderBind) {
        val token = buildString {
            append(bind.generation)
            append('|')
            append(bind.mode.prefId)
            append('|')
            append(bind.crop)
            append('|')
            append(bind.pages.size)
            append('|')
            append(bind.pages.firstOrNull())
            append('|')
            append(bind.pages.lastOrNull())
        }
        if (token == boundToken && viewer != null) return
        if (bind.pages.isEmpty()) {
            boundToken = token
            viewer?.destroy()
            viewer = null
            viewerMode = null
            viewerCrop = null
            viewerContainer.removeAllViews()
            return
        }

        val sameGeneration = boundGeneration == bind.generation
        val resume = (
            if (sameGeneration) viewModel.currentPageIndex.value else bind.initialPage
            ).coerceIn(0, bind.pages.lastIndex)
        val resumeOffset = if (sameGeneration || resume != bind.initialPage) 0 else viewModel.initialPageOffset.value
        currentSettings = currentSettings.copy(cropBorders = bind.crop)
        val recreate = viewer == null || viewerMode != bind.mode || viewerCrop != bind.crop
        if (recreate) {
            viewer?.destroy()
            viewerContainer.removeAllViews()
            val created = createViewer(bind.mode)
            viewer = created
            viewerMode = bind.mode
            viewerCrop = bind.crop
            viewerContainer.addView(created.getView(), MATCH_PARENT, MATCH_PARENT)
        }

        val chapterId = viewModel.currentChapterId
        val title = viewModel.activeChapterTitle.value.ifBlank { "Chapter" }
        viewer?.setChapters(
            ViewerChapters(
                current = ReaderChapterInfo(chapterId, title),
                pages = bind.pages.mapIndexed { index, url -> ReaderPage(index, url, chapterId) },
                prev = viewModel.previousChapter.value?.let { ReaderChapterInfo(it.url, it.name) },
                next = viewModel.nextChapter.value?.let { ReaderChapterInfo(it.url, it.name) },
                requestedPage = resume,
                requestedOffset = resumeOffset,
                generation = bind.generation,
            ),
        )
        boundGeneration = bind.generation
        boundToken = token
        pageSeek.max = bind.pages.lastIndex.coerceAtLeast(0)
        if (!seeking) {
            pageSeek.progress = resume
            pageLabel.text = "${resume + 1} / ${bind.pages.size}"
        }
    }

    private fun renderStatus() {
        if (!::viewModel.isInitialized) return
        val pages = viewModel.pages.value
        val loading = viewModel.isLoading.value
        val error = viewModel.pageError.value
        val show = pages.isEmpty() && (loading || error != null)
        statusOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        progressBar.visibility = if (loading && error == null) View.VISIBLE else View.GONE
        when (error) {
            is ReaderPageError.CloudflareBlocked -> {
                statusMessage.text = "Cloudflare protection blocked this chapter."
                statusPrimary.visibility = View.VISIBLE
                statusPrimary.text = "Solve challenge in WebView"
                statusPrimary.setOnClickListener { solveCloudflareChallenge(error.chapterUrl, sourceReferer) }
                statusSecondary.visibility = View.VISIBLE
                statusSecondary.text = "Retry loading chapter"
                statusSecondary.setOnClickListener { reloadCurrentChapter() }
            }
            ReaderPageError.NoPages -> {
                statusMessage.text = "No pages found"
                val debug = viewModel.pageListDebugEvent.value
                if (viewModel.pageListDebugMode.value && debug != null) {
                    statusPrimary.visibility = View.VISIBLE
                    statusPrimary.text = "View debug log"
                    statusPrimary.setOnClickListener {
                        AlertDialog.Builder(this)
                            .setTitle("Page List Debug")
                            .setMessage(debug.fullText())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    statusPrimary.visibility = View.GONE
                }
                statusSecondary.visibility = View.VISIBLE
                statusSecondary.text = "Retry"
                statusSecondary.setOnClickListener { reloadCurrentChapter() }
            }
            null -> {
                statusMessage.text = "Loading chapter…"
                statusPrimary.visibility = View.GONE
                statusSecondary.visibility = View.GONE
            }
        }
    }

    private fun observePreferences() {
        val settings = app.settingsManager
        lifecycleScope.launch {
            settings.volumeKeysNav.collect { enabled ->
                currentSettings = currentSettings.copy(volumeKeys = enabled)
            }
        }
        lifecycleScope.launch {
            settings.readerTapZones.collect { enabled ->
                currentSettings = currentSettings.copy(tapZones = enabled)
            }
        }
        lifecycleScope.launch {
            settings.keepScreenOn.collect { enabled ->
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            settings.flagSecure.collect { enabled ->
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        lifecycleScope.launch {
            settings.readerOrientation.collect { applyOrientation(it) }
        }
        lifecycleScope.launch {
            settings.readerBrightness.collect { applyBrightness(it) }
        }
    }

    private fun applyOrientation(value: String) {
        requestedOrientation = when (value) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "locked" -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyBrightness(value: String) {
        val params = window.attributes
        params.screenBrightness = when (value) {
            "dim" -> 0.15f
            "bright" -> 1f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = params
    }

    private fun setMenuVisible(visible: Boolean) {
        menuVisible = visible
        menuRoot.visibility = if (visible) View.VISIBLE else View.GONE
        if (::viewModel.isInitialized) viewModel.setMenuVisible(visible)
        val controller = WindowCompat.getInsetsController(window, viewerContainer)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dismissSettings() {
        showSettings.value = false
        settingsCompose.visibility = View.GONE
    }

    private fun createViewer(mode: ReadingMode): Viewer = when (mode) {
        ReadingMode.LEFT_TO_RIGHT -> L2RPagerViewer(this)
        ReadingMode.RIGHT_TO_LEFT -> R2LPagerViewer(this)
        ReadingMode.VERTICAL -> VerticalPagerViewer(this)
        ReadingMode.WEBTOON -> WebtoonViewer(this)
        ReadingMode.CONTINUOUS_VERTICAL -> ContinuousVerticalViewer(this)
    }

    private fun buildContent() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        viewerContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        statusOverlay = statusOverlay()
        menuRoot = menuOverlay()
        settingsCompose = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeMode by app.settingsManager.themeMode.collectAsStateWithLifecycle(initialValue = "system")
                WebtoonTheme(themeMode = themeMode) {
                    if (showSettings.value) {
                        ReaderSettingsSheet(
                            viewModel = viewModel,
                            onDismiss = { dismissSettings() },
                        )
                    }
                }
            }
        }
        root.addView(viewerContainer, MATCH_PARENT, MATCH_PARENT)
        root.addView(statusOverlay, MATCH_PARENT, MATCH_PARENT)
        root.addView(menuRoot, MATCH_PARENT, MATCH_PARENT)
        root.addView(settingsCompose, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)
    }

    private fun statusOverlay(): FrameLayout {
        progressBar = ProgressBar(this)
        statusMessage = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        statusPrimary = Button(this)
        statusSecondary = Button(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
            addView(progressBar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(statusMessage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(statusPrimary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(statusSecondary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        return FrameLayout(this).apply {
            setBackgroundColor(0xF0000000.toInt())
            isClickable = true
            visibility = View.GONE
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun menuOverlay(): LinearLayout {
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }
        val back = textButton("Back") { finish() }
        val settings = textButton("Settings") {
            showSettings.value = true
            settingsCompose.visibility = View.VISIBLE
        }
        val top = horizontalBar().apply {
            addView(back)
            addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(settings)
        }

        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "1 / 1"
        }
        pageSeek = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val url = viewModel.pages.value.getOrNull(progress) ?: return
                        viewer?.moveToPage(ReaderPage(progress, url, viewModel.currentChapterId))
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        seeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        seeking = false
                    }
                },
            )
        }
        prevButton = textButton("Previous") { viewModel.loadPreviousChapter() }
        nextButton = textButton("Next") { viewModel.loadNextChapter() }
        cropButton = textButton("Crop off") { viewModel.setCropBorders(!viewModel.cropBorders.value) }
        modeButton = textButton("Webtoon") { viewModel.setReadingMode(viewModel.readingMode.value.next()) }
        val actions = horizontalBar().apply {
            addView(prevButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(cropButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(modeButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(nextButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            val pad = dp(8)
            setPadding(pad, pad, pad, dp(16))
            addView(pageLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(pageSeek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            isClickable = false
            visibility = View.GONE
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun horizontalBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xCC000000.toInt())
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun textButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class ReaderBind(
        val pages: List<String>,
        val generation: Long,
        val initialPage: Int,
        val mode: ReadingMode,
        val crop: Boolean,
    )

    companion object {
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_WEBTOON_ID = "webtoon_id"
        private const val EXTRA_CHAPTER_ID = "chapter_id"
        private const val EXTRA_CHAPTER_TITLE = "chapter_title"

        fun intent(
            context: Context,
            sourceId: String,
            webtoonId: String,
            chapterId: String,
            chapterTitle: String,
        ): Intent = Intent(context, ReaderActivity::class.java).apply {
            putExtra(EXTRA_SOURCE_ID, sourceId)
            putExtra(EXTRA_WEBTOON_ID, webtoonId)
            putExtra(EXTRA_CHAPTER_ID, chapterId)
            putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255f).toInt().coerceIn(0, 255),
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255),
    )
}

private fun isLight(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000 > 160
}
