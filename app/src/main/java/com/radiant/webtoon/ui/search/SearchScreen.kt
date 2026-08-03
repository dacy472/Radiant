package com.radiant.webtoon.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.ui.components.WebtoonThumbnailCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    onWebtoonClick: (String, String) -> Unit, // sourceId, webtoonId
    onBrowseClick: (String) -> Unit, // sourceId
    initialQuery: String? = null,
    migrateFromMangaId: String? = null,
    onMigrationComplete: () -> Unit = {}
) {
    val viewModel: GlobalSearchViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return GlobalSearchViewModel(repository) as T
        }
    })
    val filterPinned by viewModel.filterPinned.collectAsState()
    val filterHasResults by viewModel.filterHasResults.collectAsState()
    val migrateFromManga by viewModel.migrateFromManga.collectAsState()
    
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val sources by viewModel.activeSources.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val uiError by viewModel.error.collectAsState()

    val migrationViewModel: com.radiant.webtoon.ui.browse.MigrationViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return com.radiant.webtoon.ui.browse.MigrationViewModel(repository) as T
        }
    })
    val isMigrating by migrationViewModel.isMigrating.collectAsState()

    androidx.compose.runtime.LaunchedEffect(initialQuery) {
        if (initialQuery != null) {
            val manga = if (migrateFromMangaId != null) {
                repository.mangaDao.getManga(migrateFromMangaId, "") 
                null 
            } else null
            
            // Re-fetch the specific manga if migrating
            val targetManga = if (migrateFromMangaId != null) {
                repository.mangaDao.getFavorites().find { it.url == migrateFromMangaId }
            } else null

            viewModel.setInitialQuery(initialQuery, targetManga)
        }
    }

    var showMigrationDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, com.radiant.webtoon.core.model.Webtoon>?>(null) }

    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    androidx.compose.runtime.LaunchedEffect(uiError) {
        if (uiError != null && results.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                message = uiError!!,
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    TextField(
                        value = query,
                        onValueChange = { viewModel.onQueryChange(it) },
                        placeholder = { Text("Search all sources...") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                viewModel.search(query)
                            }
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (isSearching) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        "Searching...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        ),
                        singleLine = true
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // FILTER CHIPS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !filterPinned,
                    onClick = { if (filterPinned) viewModel.toggleFilterPinned() },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = filterPinned,
                    onClick = { if (!filterPinned) viewModel.toggleFilterPinned() },
                    label = { Text("Pinned") }
                )
                FilterChip(
                    selected = filterHasResults,
                    onClick = { viewModel.toggleFilterHasResults() },
                    label = { Text("Has results") }
                )
            }

            val displaySources = sources.filter { source ->
                if (filterPinned && !source.isPinned) return@filter false
                if (filterHasResults) {
                    val sourceResult = results.find { it.sourceId == source.id }
                    if (sourceResult == null || sourceResult.webtoons.isEmpty() || sourceResult.error != null) {
                        return@filter false
                    }
                }
                true
            }

            if (displaySources.isEmpty()) {
                if (sources.isEmpty()) {
                    NoSourcesInstalled(onRefresh = { viewModel.refreshSources() })
                } else if (filterHasResults && !isSearching) {
                    SearchPlaceholder("No results matching filters.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(displaySources) { source ->
                        val sourceResult = results.find { it.sourceId == source.id }
                        val isSourceFetching = isSearching && (sourceResult == null || (sourceResult.webtoons.isEmpty() && sourceResult.error == null))
                        
                        Column {
                            // ── Source Header Row ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = source.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = source.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (source.lang.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = source.lang.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }

                                if (isSourceFetching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    TextButton(onClick = { onBrowseClick(source.id) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("Browse")
                                    }
                                }
                            }

                            if (query.length >= 2) {
                                if (sourceResult != null) {
                                    if (sourceResult.webtoons.isEmpty() && !isSourceFetching) {
                                        // ── No results / error per source ──
                                        Text(
                                            text = sourceResult.error ?: "No results found",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                                            color = if (sourceResult.error != null)
                                                MaterialTheme.colorScheme.error
                                            else
                                                MaterialTheme.colorScheme.outline
                                        )
                                    } else if (sourceResult.webtoons.isNotEmpty()) {
                                        // ── Horizontal carousel of manga cards (Mihon-style) ──
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(sourceResult.webtoons) { webtoon ->
                                                Box(modifier = Modifier.width(110.dp)) {
                                                    WebtoonThumbnailCard(
                                                        webtoon = webtoon,
                                                        onClick = {
                                                            if (migrateFromManga != null) {
                                                                showMigrationDialog = source.id to webtoon
                                                            } else {
                                                                onWebtoonClick(source.id, webtoon.id)
                                                            }
                                                        },
                                                        sourceId = source.id
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Divider between sources ──
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        if (showMigrationDialog != null) {
            val (targetSourceId, targetWebtoon) = showMigrationDialog!!
            AlertDialog(
                onDismissRequest = { showMigrationDialog = null },
                title = { Text("Confirm Migration") },
                text = { Text("Migrate '${migrateFromManga?.title}' to '${targetWebtoon.title}' on $targetSourceId?\nThis will transfer your reading progress.") },
                confirmButton = {
                    Button(onClick = {
                        val oldManga = migrateFromManga ?: return@Button
                        migrationViewModel.migrateWebtoon(oldManga, targetWebtoon, targetSourceId) {
                            showMigrationDialog = null
                            onMigrationComplete()
                        }
                    }) {
                        Text("Migrate")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMigrationDialog = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (isMigrating) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Card(modifier = Modifier.padding(16.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Migrating progress...")
                    }
                }
            }
        }
    }
}



@Composable
fun NoSourcesInstalled(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No extensions installed",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "You need to install extensions from the Extensions tab before you can search.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRefresh) {
            Text("Refresh Extensions")
        }
    }
}

@Composable
fun SearchPlaceholder(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
