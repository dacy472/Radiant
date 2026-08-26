package com.radiant.webtoon.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.debug.PageListDebugLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListDebugScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val events by PageListDebugLogger.eventsFlow.collectAsState()
    val logFiles = remember(events) { PageListDebugLogger.listLogFiles() }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var selectedFileContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page List Debug") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            PageListDebugLogger.clearEvents()
                            PageListDebugLogger.deleteAllLogFiles()
                            Toast.makeText(context, "Debug logs cleared", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Recent empty-page diagnostics (in-memory). Tap an event to expand; use copy to share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (events.isEmpty()) {
                item {
                    Text(
                        "No page-list debug events yet. Open a chapter that returns zero pages.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(events, key = { it.id }) { event ->
                    val expanded = expandedId == event.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedId = if (expanded) null else event.id },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(event.summaryLine(), style = MaterialTheme.typography.bodyMedium)
                            event.sourceName?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                            if (expanded) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    event.fullText(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = { copyToClipboard(context, event.fullText()) },
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                                        Text("Copy")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Saved log files", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${context.filesDir}/page_list_debug/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (logFiles.isEmpty()) {
                item { Text("No log files on disk yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(logFiles, key = { it.absolutePath }) { file ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFileContent = file.name to PageListDebugLogger.readLogFile(file)
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                onClick = { copyToClipboard(context, PageListDebugLogger.readLogFile(file)) },
                            ) { Text("Copy") }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    selectedFileContent?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { selectedFileContent = null },
            title = { Text(name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedFileContent = null }) { Text("Close") }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("page_list_debug", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
