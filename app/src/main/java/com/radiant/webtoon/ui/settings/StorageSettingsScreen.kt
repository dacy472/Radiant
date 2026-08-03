package com.radiant.webtoon.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val downloadDao = remember { app.database.downloadDao() }
    val scope = rememberCoroutineScope()

    var completedDownloads by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            isLoading = true
            completedDownloads = withContext(Dispatchers.IO) { downloadDao.getCompleted() }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val grouped = remember(completedDownloads) {
        completedDownloads.groupBy { it.webtoonTitle }.toList().sortedBy { it.first.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                grouped.isEmpty() -> Text(
                    "No completed downloads",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(grouped, key = { it.first }) { (title, chapters) ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${chapters.size} chapter(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    app.repository.deleteDownloadedChapters(
                                                        chapters.map { it.chapterId to it.sourceId }
                                                    )
                                                }
                                                Toast.makeText(context, "Deleted downloads for $title", Toast.LENGTH_SHORT).show()
                                                refresh()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                                chapters.take(3).forEach { chapter ->
                                    Text(
                                        chapter.chapterTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                if (chapters.size > 3) {
                                    Text(
                                        "+ ${chapters.size - 3} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
