package com.radiant.webtoon.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.DownloadedChapter
import com.radiant.webtoon.core.local.LocalLibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val downloadedChapterDao = remember { app.database.downloadedChapterDao() }
    val settingsManager = remember { app.settingsManager }
    val scope = rememberCoroutineScope()

    var completedDownloads by remember { mutableStateOf<List<DownloadedChapter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshingLocal by remember { mutableStateOf(false) }

    val localLibraryUriName by settingsManager.localLibraryUriName.collectAsState(initial = null)
    val localLibraryPath by settingsManager.localLibraryPath.collectAsState(initial = null)
    val localFolderLabel = localLibraryUriName
        ?: localLibraryPath
        ?: LocalLibraryScanner.defaultRootDir().absolutePath

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val name = docFile?.name ?: "Custom Folder"
            scope.launch {
                settingsManager.setLocalLibraryUri(uri.toString())
                settingsManager.setLocalLibraryUriName(name)
                settingsManager.setLocalLibraryPath(null)
                app.localLibraryManager.refresh()
                Toast.makeText(context, "Local library folder updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refresh() {
        scope.launch {
            isLoading = true
            completedDownloads = withContext(Dispatchers.IO) { downloadedChapterDao.getAll() }
            isLoading = false
        }
    }

    fun refreshLocalLibrary() {
        scope.launch {
            isRefreshingLocal = true
            try {
                val result = withContext(Dispatchers.IO) { app.localLibraryManager.refresh() }
                val message = result.error ?: "Found ${result.series.size} local series"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                refresh()
            } finally {
                isRefreshingLocal = false
            }
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
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Local library folder", style = MaterialTheme.typography.titleMedium)
                            Text(
                                localFolderLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { folderPickerLauncher.launch(null) },
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Choose folder")
                                }
                                Button(
                                    onClick = { refreshLocalLibrary() },
                                    enabled = !isRefreshingLocal,
                                ) {
                                    if (isRefreshingLocal) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Completed downloads",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                when {
                    isLoading -> item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    grouped.isEmpty() -> item {
                        Text(
                            "No completed downloads",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    else -> items(grouped, key = { it.first }) { (title, chapters) ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${chapters.size} chapter(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    app.repository.deleteDownloadedChapters(
                                                        chapters.map { it.chapterId to it.sourceId },
                                                    )
                                                }
                                                Toast.makeText(context, "Deleted downloads for $title", Toast.LENGTH_SHORT).show()
                                                refresh()
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                                chapters.take(3).forEach { chapter ->
                                    Text(
                                        chapter.chapterTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                if (chapters.size > 3) {
                                    Text(
                                        "+ ${chapters.size - 3} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
