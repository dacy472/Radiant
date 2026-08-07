package com.radiant.webtoon.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.radiant.webtoon.core.model.Chapter
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
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

    LaunchedEffect(sourceId, webtoonId) {
        viewModel.loadWebtoon(sourceId, webtoonId)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshToastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()

    if (pullToRefreshState.isRefreshing && !selectionMode) {
        LaunchedEffect(true) {
            viewModel.refreshWebtoon()
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                ) {
                    item {
                        WebtoonHeader(webtoon, sourceId, isFavorite, onToggleFavorite = { viewModel.toggleFavorite() })
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
                    items(chapters, key = { it.id }) { chapter ->
                        val downloadStatus = viewModel.downloadTasks.collectAsStateWithLifecycle().value[chapter.id]
                        ChapterListItem(
                            modifier = Modifier.animatedListRow(),
                            chapter = chapter,
                            status = downloadStatus,
                            selectionMode = selectionMode,
                            selected = selectedChapterIds.contains(chapter.id),
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(chapter.id)
                                } else {
                                    onChapterClick(chapter.id, chapter.title)
                                }
                            },
                            onLongClick = { onChapterLongPress(chapter.id) },
                            onDownloadClick = {
                                when {
                                    downloadStatus == com.radiant.webtoon.core.database.DownloadStatus.COMPLETED -> {
                                        pendingDeleteChapterId = chapter.id
                                    }
                                    downloadStatus == null || downloadStatus == com.radiant.webtoon.core.database.DownloadStatus.FAILED -> {
                                        // Use the in-app queue (per-page JPG folders) so the reader can open offline
                                        viewModel.downloadChapter(context, chapter.id, chapter.title)
                                        Toast.makeText(context, "Download queued: ${chapter.title}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Pull-to-refresh indicator
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
        val chapterTitle = chapters.find { it.id == chapterId }?.title ?: "this chapter"
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
fun WebtoonHeader(webtoon: Webtoon?, sourceId: String, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    val context = LocalContext.current
    val coverHeaders = rememberSourceImageHeaders(sourceId)
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(webtoon?.thumbnail)
                .apply { coverHeaders.forEach { (key, value) -> addHeader(key, value) } }
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .blur(16.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.6f
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
                .fillMaxWidth()
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(webtoon?.thumbnail)
                    .apply { coverHeaders.forEach { (key, value) -> addHeader(key, value) } }
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .width(110.dp)
                    .height(160.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp), clip = true)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
                success = { SubcomposeAsyncImageContent() }
            )
        Column(modifier = Modifier.padding(start = 16.dp)) {
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
    Column(modifier = Modifier.padding(16.dp).clickable { expanded = !expanded }) {
        Box {
            Text(
                text = description ?: "No description available",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!expanded && !description.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                            )
                        )
                )
            }
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
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
    val titleColor = if (chapter.read && !selected) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

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
            Text(
                text = chapter.title,
                color = titleColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Text(
                text = chapter.date ?: "Yesterday",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
