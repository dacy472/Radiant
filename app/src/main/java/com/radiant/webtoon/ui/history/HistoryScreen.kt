package com.radiant.webtoon.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.size.Scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
import com.radiant.webtoon.ui.components.DownloadManagerIconButton
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import com.radiant.webtoon.ui.components.animatedListRow
import com.radiant.webtoon.ui.components.listItemRippleClickable
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import com.radiant.webtoon.ui.components.SpringAlertDialog
import java.text.SimpleDateFormat
import java.util.*

data class HistoryItem(
    val id: String,
    val webtoonTitle: String,
    val chapterTitle: String,
    val webtoonThumbnail: String?,
    val lastRead: Long,
    val sourceId: String,
    val webtoonId: String,
    val chapterId: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    repository: WebtoonRepository,
    onWebtoonClick: (String, String) -> Unit, // sourceId, webtoonId
    onChapterClick: (String, String, String, String) -> Unit, // sourceId, webtoonId, chapterId, chapterTitle
    onDownloadQueueClick: () -> Unit = {},
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModelFactory(repository)
    )
    val historyItems by viewModel.historyState.collectAsStateWithLifecycle()
    val dateFormatPattern by (androidx.compose.ui.platform.LocalContext.current.applicationContext as WebtoonApplication)
        .settingsManager.dateFormat.collectAsState(initial = "MM/dd/yyyy")
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<HistoryItem?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("History") },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (historyItems.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear History")
                        }
                    }
                    DownloadManagerIconButton(onClick = onDownloadQueueClick)
                }
            )
        }
    ) { padding ->
        pendingRemoval?.let { pending ->
            SpringAlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text("Remove from history") },
                text = {
                    Text("Remove \"${pending.webtoonTitle}\" from history? Other titles stay. Chapters already in your library are not deleted.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeWebtoon(pending.sourceId, pending.webtoonId)
                            pendingRemoval = null
                        }
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
        if (showClearDialog) {
            SpringAlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear History") },
                text = { Text("Are you sure you want to clear your reading history? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearHistory()
                            showClearDialog = false
                        }
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No reading history yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                items(historyItems, key = { it.id }) { item ->
                    HistoryItemRow(
                        modifier = Modifier.animatedListRow(),
                        item = item,
                        dateFormatPattern = dateFormatPattern,
                        onClick = {
                            onChapterClick(item.sourceId, item.webtoonId, item.chapterId, item.chapterTitle)
                        },
                        onCoverClick = {
                            onWebtoonClick(item.sourceId, item.webtoonId)
                        },
                        onRemove = { pendingRemoval = item },
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(
    item: HistoryItem,
    modifier: Modifier = Modifier,
    dateFormatPattern: String = "MM/dd/yyyy",
    onClick: () -> Unit,
    onCoverClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val coverHeaders = rememberSourceImageHeaders(sourceId = item.sourceId)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .listItemRippleClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadiantAsyncImage(
                url = item.webtoonThumbnail.orEmpty(),
                contentDescription = "Cover",
                headers = coverHeaders,
                sourceId = item.sourceId,
                scale = Scale.FILL,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .listItemRippleClickable(onClick = onCoverClick),
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.webtoonTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )

                Text(
                    text = item.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )

                Text(
                    text = formatRelativeTime(item.lastRead, dateFormatPattern),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${item.webtoonTitle} from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 80.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

fun formatRelativeTime(timestamp: Long, dateFormatPattern: String = "MM/dd/yyyy"): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val fallbackPattern = dateFormatPattern.ifBlank { "MM/dd/yyyy" }

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> runCatching {
            SimpleDateFormat(fallbackPattern, Locale.getDefault()).format(Date(timestamp))
        }.getOrElse {
            SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

