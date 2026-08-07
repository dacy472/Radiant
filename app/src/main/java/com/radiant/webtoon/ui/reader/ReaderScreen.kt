package com.radiant.webtoon.ui.reader

import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val sourceHeaders by viewModel.sourceHeaders.collectAsStateWithLifecycle()
    
    val readingMode by viewModel.readingMode.collectAsStateWithLifecycle()
    val backgroundColor by viewModel.backgroundColor.collectAsStateWithLifecycle()
    val cropBorders by viewModel.cropBorders.collectAsStateWithLifecycle()
    val nextChapter by viewModel.nextChapter.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    var markedRead by remember(chapterId) { mutableStateOf(false) }

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
    
    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { 
        if (nextChapter != null) sortedPages.size + 1 else sortedPages.size 
    })

    // Page counter — derived from the list/pager state
    val currentPage by remember(readingMode) {
        derivedStateOf {
            if (readingMode == 0) listState.firstVisibleItemIndex + 1
            else pagerState.currentPage + 1
        }
    }

    // Mark chapter read near the end (last page or ~90%)
    LaunchedEffect(currentPage, sortedPages.size, chapterId) {
        if (!markedRead && sortedPages.isNotEmpty()) {
            val threshold = (sortedPages.size * 0.9f).toInt().coerceAtLeast(1)
            if (currentPage >= threshold || currentPage >= sortedPages.size) {
                markedRead = true
                viewModel.markChapterReadWhenComplete(chapterId, webtoonId)
            }
        }
    }

    // Convert source headers to a Map for Coil's ImageRequest
    val headersMap = remember(sourceHeaders) {
        val map = mutableMapOf<String, String>()
        for (i in 0 until sourceHeaders.size) {
            map[sourceHeaders.name(i)] = sourceHeaders.value(i)
        }
        map
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
                    Text(
                        "No pages found",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }
        } else {
            if (readingMode == 0) {
                VirtualizedWebtoonColumn(
                    pages = sortedPages,
                    listState = listState,
                    headersMap = headersMap,
                    screenWidthPx = screenWidthPx,
                    cropBorders = cropBorders,
                    nextChapter = nextChapter,
                    onTap = { showOverlay = !showOverlay },
                    onNextChapterClick = {
                        val chapter = nextChapter ?: return@VirtualizedWebtoonColumn
                        viewModel.loadChapter(sourceId, webtoonId, chapter.url, chapter.name)
                        coroutineScope.launch {
                            listState.scrollToItem(0)
                        }
                    },
                )
            } else {
                // Horizontal pager (LTR or RTL)
                val isRtl = readingMode == 2
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showOverlay = !showOverlay })
                        },
                    beyondBoundsPageCount = 2,
                    reverseLayout = isRtl,
                    key = { it }
                ) { pageIndex ->
                    if (pageIndex < sortedPages.size) {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }
                        RadiantAsyncImage(
                            url = sortedPages[pageIndex],
                            contentDescription = "Page ${pageIndex + 1}",
                            modifier = Modifier
                                .fillMaxSize()
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
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                            headers = headersMap,
                            widthPx = screenWidthPx,
                            precision = Precision.INEXACT,
                            allowHardware = false,
                            contentScale = if (cropBorders) ContentScale.FillWidth else ContentScale.Fit,
                        )
                    } else {
                        if (nextChapter != null) {
                            val chapter = nextChapter!!
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.loadChapter(sourceId, webtoonId, chapter.url, chapter.name)
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(0)
                                        }
                                    },
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
                        Column {
                            // ── Page scrubbing Slider + Counter Row ──
                            if (sortedPages.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val sliderValue = if (readingMode == 0) {
                                        listState.firstVisibleItemIndex.toFloat()
                                    } else {
                                        pagerState.currentPage.toFloat()
                                    }
                                    
                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { newValue ->
                                            coroutineScope.launch {
                                                if (readingMode == 0) {
                                                    listState.scrollToItem(newValue.toInt())
                                                } else {
                                                    pagerState.scrollToPage(newValue.toInt())
                                                }
                                            }
                                        },
                                        valueRange = 0f..(sortedPages.size - 1).coerceAtLeast(0).toFloat(),
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                        )
                                    )
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Text(
                                        text = "$currentPage / ${sortedPages.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.width(60.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                                    )
                                }
                            }

                            // ── Quick controls (settings live in the top bar) ──
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
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
                            }
                        }
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
                    text = "Please solve captcha in WebView",
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
                    Text("Open site in WebView")
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
