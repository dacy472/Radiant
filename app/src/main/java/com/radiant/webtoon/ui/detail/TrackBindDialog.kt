package com.radiant.webtoon.ui.detail

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.core.database.Track
import com.radiant.webtoon.core.tracker.TrackerEntry
import com.radiant.webtoon.ui.components.SpringAlertDialog
import com.radiant.webtoon.ui.components.animatedListRow
import com.radiant.webtoon.ui.components.listItemRippleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackBindDialog(
    mangaUrl: String,
    mangaSource: String,
    mangaTitle: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as WebtoonApplication }
    val trackerService = remember { app.trackerService }
    val trackDao = remember { app.database.trackDao() }
    val scope = rememberCoroutineScope()

    var query by remember(mangaTitle) { mutableStateOf(mangaTitle) }
    var results by remember { mutableStateOf<List<TrackerEntry>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    SpringAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track on AniList") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            isSearching = true
                            results = runCatching { trackerService.search(query) }.getOrDefault(emptyList())
                            isSearching = false
                        }
                    },
                    enabled = !isSearching && query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSearching) "Searching..." else "Search AniList")
                }
                if (results.isEmpty() && !isSearching) {
                    Text(
                        "Search for a title to bind tracking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(results, key = { it.trackerId }) { entry ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animatedListRow()
                                .listItemRippleClickable(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            trackDao.insert(
                                                Track(
                                                    mangaUrl = mangaUrl,
                                                    mangaSource = mangaSource,
                                                    trackerId = "anilist",
                                                    remoteId = entry.trackerId,
                                                    title = entry.title
                                                )
                                            )
                                        }
                                        Toast.makeText(
                                            context,
                                            "Linked to ${entry.title}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onDismiss()
                                    }
                                }),
                            headlineContent = { Text(entry.title) },
                            supportingContent = { Text("AniList #${entry.trackerId}") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
