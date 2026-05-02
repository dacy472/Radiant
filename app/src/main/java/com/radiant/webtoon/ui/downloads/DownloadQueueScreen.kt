package com.radiant.webtoon.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.DownloadStatus
import com.radiant.webtoon.core.database.DownloadTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as WebtoonApplication
    val queueManager = app.downloadQueueManager

    val activeDownloads by queueManager.activeDownloads.collectAsStateWithLifecycle()
    val downloadSpeed by queueManager.downloadSpeed.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Download Queue")
                        if (activeDownloads.isNotEmpty()) {
                            Text(
                                text = "Speed: $downloadSpeed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (activeDownloads.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }) {
                        IconButton(onClick = {
                            activeDownloads.forEach { task ->
                                if (task.status != DownloadStatus.PAUSED) {
                                    queueManager.pause(task.chapterId, task.sourceId)
                                }
                            }
                        }) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause All")
                        }
                    } else if (activeDownloads.any { it.status == DownloadStatus.PAUSED }) {
                        IconButton(onClick = {
                            activeDownloads.forEach { task ->
                                if (task.status == DownloadStatus.PAUSED) {
                                    queueManager.resume(task.chapterId, task.sourceId)
                                }
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume All")
                        }
                    }
                    if (activeDownloads.isNotEmpty()) {
                        IconButton(onClick = {
                            activeDownloads.forEach { task ->
                                queueManager.cancel(task.chapterId, task.sourceId)
                            }
                        }) {
                            Icon(Icons.Default.ClearAll, contentDescription = "Cancel All")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (activeDownloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active downloads.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                itemsIndexed(activeDownloads, key = { _, item -> "${item.sourceId}_${item.chapterId}" }) { index, task ->
                    DownloadItemRow(
                        task = task,
                        isFirst = index == 0,
                        isLast = index == activeDownloads.lastIndex,
                        onPause = { queueManager.pause(task.chapterId, task.sourceId) },
                        onResume = { queueManager.resume(task.chapterId, task.sourceId) },
                        onCancel = { queueManager.cancel(task.chapterId, task.sourceId) },
                        onMoveUp = { queueManager.reorder(index, index - 1) },
                        onMoveDown = { queueManager.reorder(index, index + 1) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun DownloadItemRow(
    task: DownloadTask,
    isFirst: Boolean,
    isLast: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reordering arrows
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
            }
            IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(text = task.webtoonTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(text = task.chapterTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val statusColor = when (task.status) {
                DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                DownloadStatus.PAUSED -> Color.Gray
                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = statusColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${task.downloadedPages}/${task.totalPages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                } else {
                    Text(
                        text = task.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Actions
        Row {
            if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                }
            } else {
                IconButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause")
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Clear, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
