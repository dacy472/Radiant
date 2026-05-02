package com.radiant.webtoon.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.radiant.webtoon.core.model.Chapter
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.ChapterDownloadWorker
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebtoonDetailScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    sourceId: String,
    webtoonId: String,
    onBackClick: () -> Unit,
    onChapterClick: (String, String) -> Unit,
    onWebViewClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // Inject from the unified WebtoonDatabase (has Manga + Chapter tables)
    val db = com.radiant.webtoon.core.database.WebtoonDatabase.getDatabase(context)
    val mangaDao = db.mangaDao()
    val chapterDao = db.chapterDao()
    val viewModel: WebtoonDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return WebtoonDetailViewModel(repository, mangaDao, chapterDao, db.downloadDao()) as T
        }
    })

    val webtoon by viewModel.webtoon.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    
    val source = remember(sourceId) { repository.extensionManager.getSource(sourceId) }

    LaunchedEffect(sourceId, webtoonId) {
        viewModel.loadWebtoon(sourceId, webtoonId)
    }

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()

    if (pullToRefreshState.isRefreshing) {
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
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    // Overflow menu
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            if (isLoading) {
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
                    item {
                        WebtoonActions(
                            webtoonUrl = webtoon?.id ?: webtoonId,
                            onWebViewClick = {
                                val relativeUrl = webtoon?.url?.takeIf { it.isNotBlank() } ?: webtoonId
                                val baseUrl = source?.baseUrl ?: ""
                                val absoluteUrl = if (relativeUrl.startsWith("http")) relativeUrl else (baseUrl + relativeUrl)
                                onWebViewClick(absoluteUrl)
                            },
                            onTrackClick = {
                                Toast.makeText(context, "Tracking coming soon", Toast.LENGTH_SHORT).show()
                            },
                            onDownloadClick = {
                                Toast.makeText(context, "Batch download coming soon", Toast.LENGTH_SHORT).show()
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
                            chapter = chapter,
                            status = downloadStatus,
                            onClick = { onChapterClick(chapter.id, chapter.title) },
                            onDownloadClick = {
                                // Only enqueue if not already completed or in-progress
                                if (downloadStatus == null || downloadStatus == com.radiant.webtoon.core.database.DownloadStatus.FAILED) {
                                    val inputData = Data.Builder()
                                        .putString(ChapterDownloadWorker.KEY_SOURCE_ID, sourceId)
                                        .putString(ChapterDownloadWorker.KEY_WEBTOON_ID, webtoonId)
                                        .putString(ChapterDownloadWorker.KEY_CHAPTER_ID, chapter.id)
                                        .putString(ChapterDownloadWorker.KEY_WEBTOON_TITLE, webtoon?.title ?: webtoonId)
                                        .putString(ChapterDownloadWorker.KEY_CHAPTER_TITLE, chapter.title)
                                        .build()
                                    val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                                        .setInputData(inputData)
                                        .addTag("download_${chapter.id}")
                                        .build()
                                    WorkManager.getInstance(context).enqueue(request)
                                    Toast.makeText(context, "Download queued: ${chapter.title}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            // Pull-to-refresh indicator
            PullToRefreshContainer(
                modifier = Modifier.align(Alignment.TopCenter),
                state = pullToRefreshState,
            )
        }
    }
}

@Composable
fun WebtoonHeader(webtoon: Webtoon?, sourceId: String, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = webtoon?.thumbnail,
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
            AsyncImage(
                model = webtoon?.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .width(110.dp)
                    .height(160.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp), clip = true),
                contentScale = ContentScale.Crop
            )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = webtoon?.title ?: "Loading...",
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

@Composable
fun ChapterListItem(
    chapter: Chapter,
    status: com.radiant.webtoon.core.database.DownloadStatus?,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text(chapter.title) },
        supportingContent = { Text(chapter.date ?: "Yesterday") },
        trailingContent = {
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
                    tint = if (status != null) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
}
