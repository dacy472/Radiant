package com.radiant.webtoon.ui.reader

import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.imageLoader
import coil.size.Precision
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.radiant.webtoon.core.settings.SettingsManager
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    sourceId: String,
    webtoonId: String,
    chapterId: String,
    chapterTitle: String,
    onBackClick: () -> Unit,
    onOpenWebView: (url: String) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settingsManager = remember { SettingsManager(context) }
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }

    // Soft-evict decoded pages when leaving the reader to free heap
    DisposableEffect(Unit) {
        onDispose {
            context.imageLoader.memoryCache?.clear()
        }
    }

    val keepScreenOn by settingsManager.keepScreenOn.collectAsStateWithLifecycle(initialValue = true)
    val volumeKeysNav by settingsManager.volumeKeysNav.collectAsStateWithLifecycle(initialValue = false)

    val viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModelFactory(context.applicationContext as android.app.Application, repository)
    )
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val pageError by viewModel.pageError.collectAsStateWithLifecycle()
    val pageListDebugEvent by viewModel.pageListDebugEvent.collectAsStateWithLifecycle()
    val pageListDebugMode by viewModel.pageListDebugMode.collectAsStateWithLifecycle()
    val sourceHeaders by viewModel.sourceHeaders.collectAsStateWithLifecycle()
    
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val backgroundColor by viewModel.backgroundColor.collectAsStateWithLifecycle()
    val cropBorders by viewModel.cropBorders.collectAsStateWithLifecycle()
    val nextChapter by viewModel.nextChapter.collectAsStateWithLifecycle()
    val hasNextChapter by viewModel.hasNextChapter.collectAsStateWithLifecycle()
    val hasPreviousChapter by viewModel.hasPreviousChapter.collectAsStateWithLifecycle()
    val resetPositionTick by viewModel.resetPositionTick.collectAsStateWithLifecycle()
    val activeChapterId by viewModel.activeChapterId.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var markedRead by remember(activeChapterId) { mutableStateOf(false) }

    LaunchedEffect(sourceId, webtoonId, chapterId) {
        markedRead = false
        viewModel.loadChapter(sourceId, webtoonId, chapterId, chapterTitle)
    }

    // Apply keep screen on flag
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Explicitly sort pages by their index to guarantee top-to-bottom order
    val sortedPages = remember(pages) {
        pages.mapIndexed { index, url -> index to url }
            .sortedBy { it.first }
            .map { it.second }
    }

    var showOverlay by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showPageListDebug by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { 
        if (nextChapter != null) sortedPages.size + 1 else sortedPages.size 
    })

    LaunchedEffect(resetPositionTick) {
        if (resetPositionTick == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        pagerState.scrollToPage(0)
    }

    // Page counter — derived from the list/pager state
    val currentPage by remember(readingMode) {
        derivedStateOf {
            if (readingMode == 0) listState.firstVisibleItemIndex + 1
            else pagerState.currentPage + 1
        }
    }

    // Mark chapter read near the end (last page or ~90%)
    LaunchedEffect(currentPage, sortedPages.size, activeChapterId) {
        if (!markedRead && sortedPages.isNotEmpty() && activeChapterId.isNotBlank()) {
            val threshold = (sortedPages.size * 0.9f).toInt().coerceAtLeast(1)
            if (currentPage >= threshold || currentPage >= sortedPages.size) {
                markedRead = true
                viewModel.markChapterReadWhenComplete(activeChapterId, webtoonId)
            }
        }
    }

    // ── Immersive System Bars control ──
    val window = remember { (context as android.app.Activity).window }
    val insetsController = remember {
        androidx.core.view.WindowCompat.getInsetsController(window, view)
    }

    DisposableEffect(showOverlay) {
        if (showOverlay) {
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            // Always restore system bars when leaving the reader
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onKeyEvent { event ->
                if (!volumeKeysNav || event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        coroutineScope.launch {
                            if (readingMode == 0) {
                                val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(sortedPages.lastIndex)
                                listState.animateScrollToItem(next)
                            } else {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1)
                                )
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        coroutineScope.launch {
                            if (readingMode == 0) {
                                val prev = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(prev)
                            } else {
                                pagerState.animateScrollToPage(
                                    (pagerState.currentPage - 1).coerceAtLeast(0)
                                )
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (sortedPages.isEmpty()) {
            when (val error = pageError) {
                is ReaderPageError.CloudflareBlocked -> {
                    CloudflareReaderOverlay(
                        onOpenWebView = { onOpenWebView(error.chapterUrl) },
                        onRetry = {
                            viewModel.loadChapter(sourceId, webtoonId, chapterId, chapterTitle)
                        },
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No pages found",
                            color = Color.White,
                        )
                        if (pageListDebugMode && pageListDebugEvent != null) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { showPageListDebug = true }) {
                                Text("View debug log")
                            }
                        }
                    }
                }
            }
        } else {
            if (readingMode == 0) {
                VirtualizedWebtoonColumn(
                    pages = sortedPages,
                    listState = listState,
                    headers = sourceHeaders,
                    sourceId = sourceId,
                    screenWidthPx = screenWidthPx,
                    cropBorders = cropBorders,
                    nextChapter = nextChapter,
                    onTap = { showOverlay = !showOverlay },
                    onNextChapterClick = {
                        viewModel.loadNextChapter()
                    },
                )
            } else {
                // Horizontal pager (LTR or RTL)
                val isRtl = readingMode == 2
                val onPagerTap: (Offset, Int) -> Unit = { tap, widthPx ->
                    val x = tap.x
                    val width = widthPx.toFloat().coerceAtLeast(1f)
                    val goNext = when {
                        x < width / 3f -> isRtl
                        x > width * (2f / 3f) -> !isRtl
                        else -> null
                    }
                    if (goNext == null) {
                        showOverlay = !showOverlay
                    } else {
                        coroutineScope.launch {
                            val atLast = pagerState.currentPage >= pagerState.pageCount - 1
                            val atFirst = pagerState.currentPage <= 0
                            if (goNext && atLast) {
                                viewModel.loadNextChapter()
                                return@launch
                            }
                            if (!goNext && atFirst) {
                                viewModel.loadPreviousChapter()
                                return@launch
                            }
                            val target = if (goNext) {
                                (pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1)
                            } else {
                                (pagerState.currentPage - 1).coerceAtLeast(0)
                            }
                            if (target != pagerState.currentPage) {
                                pagerState.animateScrollToPage(target)
                            }
                        }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = 2,
                    reverseLayout = isRtl,
                    key = { it }
                ) { pageIndex ->
                    if (pageIndex < sortedPages.size) {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // Tap zones live on this page overlay (not a sibling on top of
                                // pinch) so detectTapGestures and detectTransformGestures share
                                // the same node: one finger = L/R/HUD, two fingers = zoom.
                                .pointerInput(isRtl) {
                                    detectTapGestures(
                                        onTap = { offset ->
                                            onPagerTap(offset, size.width)
                                        },
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 4f)
                                        if (scale > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    }
                                },
                        ) {
                            RadiantAsyncImage(
                                url = sortedPages[pageIndex],
                                contentDescription = "Page ${pageIndex + 1}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                    },
                                headers = sourceHeaders,
                                sourceId = sourceId,
                                widthPx = screenWidthPx,
                                precision = Precision.INEXACT,
                                allowHardware = false,
                                contentScale = if (cropBorders) ContentScale.FillWidth else ContentScale.Fit,
                            )
                        }
                    } else {
                        if (nextChapter != null) {
                            val chapter = nextChapter!!
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(isRtl) {
                                        detectTapGestures(
                                            onTap = { offset ->
                                                onPagerTap(offset, size.width)
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.loadNextChapter() },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.NavigateNext, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Next: ${chapter.name}")
                                }
                            }
                        }
                    }
                }
            }

            // ── Floating pill page counter — always visible, hidden behind overlay ──
            AnimatedVisibility(
                visible = !showOverlay,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "$currentPage / ${sortedPages.size}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ── Immersive Overlay (TopAppBar + bottom controls) ──
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(chapterTitle, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                // ── Bottom bar — slides in from bottom ──
                AnimatedVisibility(
                    visible = showOverlay,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                )
                            )
                            .padding(bottom = 24.dp, top = 48.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { viewModel.setCropBorders(!cropBorders) }) {
                                Icon(
                                    Icons.Default.Crop,
                                    contentDescription = if (cropBorders) "Crop borders on" else "Crop borders off",
                                    tint = if (cropBorders) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                            IconButton(onClick = {
                                val nextMode = (readingMode + 1) % 3
                                viewModel.setReadingMode(nextMode)
                            }) {
                                val (modeIcon, modeLabel) = when (readingMode) {
                                    1 -> Icons.Default.SwapHoriz to "Left to right"
                                    2 -> Icons.Default.SwapHoriz to "Right to left"
                                    else -> Icons.Default.SwapVert to "Vertical scroll"
                                }
                                Icon(modeIcon, contentDescription = "Reading mode: $modeLabel", tint = Color.White)
                            }
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Display settings",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 88.dp, bottom = 112.dp, end = 8.dp)
                        .width(48.dp)
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(percent = 50),
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = { viewModel.loadPreviousChapter() },
                        enabled = hasPreviousChapter,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Previous chapter",
                            tint = if (hasPreviousChapter) Color.White else Color.White.copy(alpha = 0.38f),
                        )
                    }

                    if (sortedPages.size > 1) {
                        val pageIndex = if (readingMode == 0) {
                            listState.firstVisibleItemIndex
                        } else {
                            pagerState.currentPage.coerceAtMost(sortedPages.lastIndex)
                        }
                        VerticalPageScrubber(
                            pageIndex = pageIndex,
                            pageCount = sortedPages.size,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            onPageChange = { page ->
                                coroutineScope.launch {
                                    if (readingMode == 0) {
                                        listState.scrollToItem(page)
                                    } else {
                                        pagerState.scrollToPage(page)
                                    }
                                }
                            },
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    IconButton(
                        onClick = { viewModel.loadNextChapter() },
                        enabled = hasNextChapter,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next chapter",
                            tint = if (hasNextChapter) Color.White else Color.White.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }

        if (showSettingsSheet) {
            ReaderSettingsSheet(
                viewModel = viewModel,
                onDismiss = { showSettingsSheet = false }
            )
        }

        if (showPageListDebug && pageListDebugEvent != null) {
            PageListDebugOverlay(
                event = pageListDebugEvent!!,
                onDismiss = { showPageListDebug = false },
            )
        }
    }
}

@Composable
private fun VerticalPageScrubber(
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    onPageChange: (Int) -> Unit,
) {
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    val fraction = if (lastIndex == 0) {
        0f
    } else {
        pageIndex.coerceIn(0, lastIndex) / lastIndex.toFloat()
    }
    val thumbColor = MaterialTheme.colorScheme.primary
    val inactiveTrack = Color.White.copy(alpha = 0.3f)
    val activeTrack = thumbColor.copy(alpha = 0.85f)

    fun pageForY(y: Float, height: Float): Int {
        val h = height.coerceAtLeast(1f)
        val t = (y / h).coerceIn(0f, 1f)
        return (t * lastIndex).roundToInt().coerceIn(0, lastIndex)
    }

    Box(
        modifier = modifier
            .pointerInput(lastIndex) {
                detectTapGestures { offset ->
                    onPageChange(pageForY(offset.y, size.height.toFloat()))
                }
            }
            .pointerInput(lastIndex) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onPageChange(pageForY(change.position.y, size.height.toFloat()))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val thumbRadius = 7.dp.toPx()
            val trackWidth = 3.dp.toPx()
            val top = thumbRadius
            val bottom = size.height - thumbRadius
            val usable = (bottom - top).coerceAtLeast(1f)
            val thumbY = top + fraction * usable

            drawLine(
                color = inactiveTrack,
                start = Offset(cx, top),
                end = Offset(cx, bottom),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = activeTrack,
                start = Offset(cx, top),
                end = Offset(cx, thumbY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(cx, thumbY),
            )
        }
    }
}

@Composable
private fun PageListDebugOverlay(
    event: com.radiant.webtoon.core.debug.PageListDebugLogger.PageListDebugEvent,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A1A),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Page List Debug",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    event.fullText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(scrollState),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("page_list_debug", event.fullText()),
                            )
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Copy") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun CloudflareReaderOverlay(
    onOpenWebView: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A1A),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Cloudflare Protection Active",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Solve the Cloudflare challenge to continue",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onOpenWebView,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Solve challenge in WebView")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Retry loading chapter")
                }
            }
        }
    }
}
