package com.radiant.webtoon.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.radiant.webtoon.core.database.UpdateItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    onWebtoonClick: (String, String) -> Unit, // sourceId, webtoonId
    onChapterClick: (String, String, String) -> Unit // sourceId, webtoonId, chapterId
) {
    val updates by viewModel.updates.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    
    // Group updates by Date
    val groupedUpdates = remember(updates) {
        updates.groupBy { item ->
            getRelativeDateString(item.chapter.date_fetch)
        }.toSortedMap(compareBy { title ->
            // Sort keys by priority: Today, Yesterday, and then other strings
            when (title) {
                "Today" -> 0
                "Yesterday" -> 1
                else -> 2
            }
        })
    }

    val pullToRefreshState = rememberPullToRefreshState()

    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.refreshManually()
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            if (!pullToRefreshState.isRefreshing) pullToRefreshState.startRefresh()
        } else {
            pullToRefreshState.endRefresh()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                actions = {
                    IconButton(onClick = { viewModel.refreshManually() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh updates")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(pullToRefreshState.nestedScrollConnection)) {
        if (updates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No new updates found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
            ) {
                groupedUpdates.forEach { (dateGroup, items) ->
                    item {
                        Text(
                            text = dateGroup,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }

                    items(items, key = { it.chapter.url }) { update ->
                        UpdateItemRow(
                            item = update,
                            onClick = {
                                onChapterClick(update.sourceId, update.chapter.manga_id, update.chapter.url)
                            },
                            onCoverClick = {
                                onWebtoonClick(update.sourceId, update.chapter.manga_id)
                            }
                        )
                    }
                }
            }
        }
            
        PullToRefreshContainer(
            modifier = Modifier.align(Alignment.TopCenter),
            state = pullToRefreshState,
        )
    }
}
}

@Composable
fun UpdateItemRow(
    item: UpdateItem,
    onClick: () -> Unit,
    onCoverClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.webtoonThumbnail,
            contentDescription = "Cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onCoverClick)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.webtoonTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (item.chapter.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = item.chapter.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        
        IconButton(onClick = { /* TODO download chapter */ }) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download Chapter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getRelativeDateString(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    val year = cal.get(Calendar.YEAR)

    cal.timeInMillis = timestamp
    val targetDay = cal.get(Calendar.DAY_OF_YEAR)
    val targetYear = cal.get(Calendar.YEAR)

    return when {
        year == targetYear && today == targetDay -> "Today"
        year == targetYear && today - targetDay == 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
