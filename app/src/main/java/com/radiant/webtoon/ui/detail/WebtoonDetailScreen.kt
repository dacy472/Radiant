package com.radiant.webtoon.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.radiant.webtoon.core.database.Chapter
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.util.ChapterDates
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import com.radiant.webtoon.ui.components.SpringAlertDialog
import com.radiant.webtoon.ui.components.animatedListRow
import com.radiant.webtoon.ui.components.listItemRippleCombinedClickable
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WebtoonDetailScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    sourceId: String,
    webtoonId: String,
    onBackClick: () -> Unit,
    onChapterClick: (String, String) -> Unit,
    onWebViewClick: (url: String) -> Unit = {}
) {
    val context = LocalContext.current
    // Inject from the unified WebtoonDatabase (has Manga + Chapter tables)
    val db = com.radiant.webtoon.core.database.WebtoonDatabase.getDatabase(context)
    val mangaDao = db.mangaDao()
    val chapterDao = db.chapterDao()
    val viewModel: WebtoonDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return WebtoonDetailViewModel(repository, mangaDao, chapterDao, db.downloadDao(), db.downloadedChapterDao()) as T
        }
    })

    val webtoon by viewModel.webtoon.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedChapterIds by remember { mutableStateOf(setOf<String>()) }
    var pendingDeleteChapterId by remember { mutableStateOf<String?>(null) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedChapterIds = emptySet()
    }

    fun toggleSelection(chapterId: String) {
        selectedChapterIds = if (selectedChapterIds.contains(chapterId)) {
            selectedChapterIds - chapterId
        } else {
            selectedChapterIds + chapterId
        }
    }

    fun onChapterLongPress(chapterId: String) {
        if (!selectionMode) {
            selectionMode = true
            selectedChapterIds = setOf(chapterId)
        } else {
            toggleSelection(chapterId)
        }
    }

    BackHandler(enabled = selectionMode) {
        exitSelectionMode()
    }

    // Bind navigation args so the ViewModel observes Room for this title.
    // Must not call refreshFromNetwork() here — network refresh is user-initiated only.
    LaunchedEffect(sourceId, webtoonId) {
        viewModel.loadWebtoon(sourceId, webtoonId)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshToastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // PullToRefreshBox is not in Compose BOM 2024.02.01; PullToRefreshContainer is.
    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val headerParallaxY by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset * 0.5f
            } else {
                0f
            }
        }
    }

    if (pullToRefreshState.isRefreshing && !selectionMode) {
        LaunchedEffect(true) {
            viewModel.refreshFromNetwork()
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            if (!pullToRefreshState.isRefreshing) pullToRefreshState.startRefresh()
        } else {
            pullToRefreshState.endRefresh()
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { -it / 3 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { -it / 3 }
                        )
                },
                label = "detailTopBar",
            ) { inSelectionMode ->
                if (inSelectionMode) {
                    ChapterSelectionTopBar(
                        selectedCount = selectedChapterIds.size,
                        onClose = { exitSelectionMode() },
                        onMarkRead = {
                            viewModel.markChaptersRead(selectedChapterIds, true)
                            exitSelectionMode()
                        },
                        onMarkUnread = {
                            viewModel.markChaptersRead(selectedChapterIds, false)
                            exitSelectionMode()
                        },
                        onDownload = {
                            viewModel.downloadSelected(context, selectedChapterIds)
                            Toast.makeText(
                                context,
                                "Queued ${selectedChapterIds.size} chapter(s)",
                                Toast.LENGTH_SHORT,
                            ).show()
                            exitSelectionMode()
                        },
                        onDelete = { showBulkDeleteConfirm = true },
                    )
                } else {
                    TopAppBar(
                        title = { },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.refreshFromNetwork()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download unread") },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.downloadUnread(context)
                                            Toast.makeText(context, "Queued unread chapters", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download next 5") },
                                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.downloadNext(context, 5)
                                            Toast.makeText(context, "Queued next 5 chapters", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Migrate") },
                                        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            android.widget.Toast.makeText(context, "Migrate coming soon", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!selectionMode) {
                        Modifier.nestedScroll(pullToRefreshState.nestedScrollConnection)
                    } else {
                        Modifier
                    }
                )
        ) {
            if (isLoading && webtoon == null && chapters.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                ) {
                    item {
                        WebtoonHeader(
                            webtoon,
                            sourceId,
                            isFavorite,
                            parallaxY = headerParallaxY,
                            onToggleFavorite = { viewModel.toggleFavorite() },
                        )
                    }
                    error?.let { message ->
                        if (webtoon == null && chapters.isEmpty()) {
                            item {
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    item {
                        WebtoonActions(
                            webtoonUrl = webtoon?.id ?: webtoonId,
                            onWebViewClick = {
                                onWebViewClick(viewModel.getMangaPageUrl())
                            },
                            onTrackClick = { showTrackDialog = true },
                            onDownloadClick = {
                                viewModel.downloadAllMissing(context)
                                Toast.makeText(context, "Queued chapters (oldest first)", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item {
                        WebtoonDescription(webtoon?.description)
                    }
                    item {
                        Text(
                            text = "${chapters.size} chapters",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(chapters, key = { it.url }) { chapter ->
                        val downloadStatus = viewModel.downloadTasks.collectAsStateWithLifecycle().value[chapter.url]
                        ChapterListItem(
                            modifier = Modifier.animatedListRow(),
                            chapter = chapter,
                            status = downloadStatus,
                            selectionMode = selectionMode,
                            selected = selectedChapterIds.contains(chapter.url),
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(chapter.url)
                                } else {
                                    onChapterClick(chapter.url, chapter.name)
                                }
                            },
                            onLongClick = { onChapterLongPress(chapter.url) },
                            onDownloadClick = {
                                when {
                                    downloadStatus == com.radiant.webtoon.core.database.DownloadStatus.COMPLETED -> {
                                        pendingDeleteChapterId = chapter.url
                                    }
                                    downloadStatus == null || downloadStatus == com.radiant.webtoon.core.database.DownloadStatus.FAILED -> {
                                        // Use the in-app queue (per-page JPG folders) so the reader can open offline
                                        viewModel.downloadChapter(context, chapter.url, chapter.name)
                                        Toast.makeText(context, "Download queued: ${chapter.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (!selectionMode) {
                PullToRefreshContainer(
                    modifier = Modifier.align(Alignment.TopCenter),
                    state = pullToRefreshState,
                )
            }
        }
    }

    if (showTrackDialog) {
        TrackBindDialog(
            mangaUrl = webtoonId,
            mangaSource = sourceId,
            mangaTitle = webtoon?.title ?: "",
            onDismiss = { showTrackDialog = false }
        )
    }

    pendingDeleteChapterId?.let { chapterId ->
        val chapterTitle = chapters.find { it.url == chapterId }?.name ?: "this chapter"
        SpringAlertDialog(
            onDismissRequest = { pendingDeleteChapterId = null },
            title = { Text("Delete download?") },
            text = { Text("This will remove the downloaded pages for \"$chapterTitle\" from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloadedChapter(chapterId)
                    pendingDeleteChapterId = null
                    Toast.makeText(context, "Download deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChapterId = null }) { Text("Cancel") }
            },
        )
    }

    if (showBulkDeleteConfirm) {
        SpringAlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete downloads?") },
            text = { Text("This will remove downloaded pages for ${selectedChapterIds.size} chapter(s) from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDownloadedChapters(selectedChapterIds)
                    showBulkDeleteConfirm = false
                    exitSelectionMode()
                    Toast.makeText(context, "Downloads deleted", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun WebtoonHeader(
    webtoon: Webtoon?,
    sourceId: String,
    isFavorite: Boolean,
    parallaxY: Float = 0f,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current
    val coverHeaders = rememberSourceImageHeaders(sourceId)
    var showFullCover by remember { mutableStateOf(false) }
    val coverUrl = webtoon?.thumbnail.orEmpty()
    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(webtoon?.thumbnail)
                .headers(coverHeaders)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { translationY = parallaxY }
                .blur(24.dp)
                .alpha(0.35f),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        startY = 0f
                    )
                )
        )
        Row(
            modifier = Modifier
                .padding(top = 88.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(webtoon?.thumbnail)
                    .headers(coverHeaders)
                    .crossfade(true)
                    .build(),
                contentDescription = "Cover",
                modifier = Modifier
                    .width(110.dp)
                    .height(160.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp), clip = true)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = coverUrl.isNotBlank()) { showFullCover = true },
                contentScale = ContentScale.Crop,
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
                success = { SubcomposeAsyncImageContent() }
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
            Text(
                text = webtoon?.title ?: if (webtoon == null) "Loading..." else "Unknown title",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = webtoon?.author?.takeIf { it.isNotBlank() } ?: "Unknown author",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status Chip
                val statusText = webtoon?.status ?: "Unknown"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule, 
                            contentDescription = null, 
                            modifier = Modifier.size(12.dp), 
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = sourceId.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onToggleFavorite,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFavorite) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                    contentColor = if (isFavorite) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(if (isFavorite) " In library" else " Add to library", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    }

    if (showFullCover && coverUrl.isNotBlank()) {
        CoverViewerDialog(
            coverUrl = coverUrl,
            headers = coverHeaders,
            onDismiss = { showFullCover = false },
        )
    }
}

@Composable
private fun CoverViewerDialog(
    coverUrl: String,
    headers: okhttp3.Headers,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClick = onDismiss),
        ) {
            RadiantAsyncImage(
                url = coverUrl,
                contentDescription = "Cover",
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                headers = headers,
                contentScale = ContentScale.Fit,
                crossfade = true,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
fun WebtoonActions(
    webtoonUrl: String,
    onWebViewClick: () -> Unit,
    onTrackClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Tracking — coming soon
        ActionButton(
            icon = Icons.Default.Bookmark,
            label = "Tracking",
            tint = Color.Gray,
            onClick = onTrackClick
        )
        // WebView — wired
        ActionButton(
            icon = Icons.Default.Public,
            label = "WebView",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onWebViewClick
        )
        // Download — coming soon
        ActionButton(
            icon = Icons.Default.FileDownload,
            label = "Download",
            tint = Color.Gray,
            onClick = onDownloadClick
        )
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
fun WebtoonDescription(description: String?) {
    var expanded by remember { mutableStateOf(false) }
    val body = description?.takeIf { it.isNotBlank() } ?: "No description available"
    Column(
        modifier = Modifier
            .padding(16.dp)
            .animateContentSize(),
    ) {
        Text(
            text = "Synopsis",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = if (expanded) "Less" else "More",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = if (selectedCount == 1) "1 selected" else "$selectedCount selected",
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onMarkRead, enabled = selectedCount > 0) {
                Icon(Icons.Default.Done, contentDescription = "Mark as read")
            }
            IconButton(onClick = onMarkUnread, enabled = selectedCount > 0) {
                Icon(Icons.Default.RemoveDone, contentDescription = "Mark as unread")
            }
            IconButton(onClick = onDownload, enabled = selectedCount > 0) {
                Icon(Icons.Default.Download, contentDescription = "Download selected")
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(Icons.Default.Delete, contentDescription = "Delete downloads")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChapterListItem(
    chapter: Chapter,
    status: com.radiant.webtoon.core.database.DownloadStatus?,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDownloadClick: () -> Unit
) {
    val rowColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            chapter.read -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "chapterRowColor",
    )
    val textAlpha = if (chapter.read) 0.38f else 1.0f
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
    val titleWeight = if (chapter.read) FontWeight.Normal else FontWeight.Bold

    ListItem(
        modifier = modifier
            .background(rowColor)
            .listItemRippleCombinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
            ) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!chapter.read) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = chapter.name,
                    color = textColor,
                    fontWeight = titleWeight,
                )
            }
        },
        supportingContent = {
            val date = ChapterDates.formatExact(chapter.date_upload)
            if (!date.isNullOrBlank()) {
                Text(
                    text = date,
                    color = textColor,
                )
            }
        },
        trailingContent = {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(150)),
            ) {
                IconButton(onClick = onDownloadClick) {
                    val icon = when (status) {
                        com.radiant.webtoon.core.database.DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                        com.radiant.webtoon.core.database.DownloadStatus.DOWNLOADING,
                        com.radiant.webtoon.core.database.DownloadStatus.PENDING -> Icons.Default.Downloading
                        else -> Icons.Default.FileDownload
                    }
                    Icon(
                        icon,
                        contentDescription = "Download",
                        tint = if (status != null) MaterialTheme.colorScheme.primary else Color.Gray,
                    )
                }
            }
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Color.Gray.copy(alpha = 0.3f),
    )
}
