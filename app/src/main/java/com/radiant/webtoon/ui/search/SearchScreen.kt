package com.radiant.webtoon.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.radiant.webtoon.core.model.SourceSearchStatus
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.ui.components.WebtoonThumbnailCard
import com.radiant.webtoon.ui.components.coverSharedKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    onWebtoonClick: (String, String) -> Unit, // sourceId, webtoonId
    onBrowseClick: (String) -> Unit, // sourceId
    onBackClick: () -> Unit,
    onWebViewClick: (String, String) -> Unit = { _, _ -> }, // sourceId, url
    initialQuery: String? = null,
    migrateFromMangaId: String? = null,
    migrateFromSourceId: String? = null,
    onMigrationComplete: (newSourceId: String, newWebtoonId: String) -> Unit = { _, _ -> }
) {
    val viewModel: GlobalSearchViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return GlobalSearchViewModel(repository) as T
        }
    })
    val filterHasResults by viewModel.filterHasResults.collectAsState()
    val migrateFromManga by viewModel.migrateFromManga.collectAsState()
    
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val searchableSources by viewModel.searchableSources.collectAsState()
    val installedCount by viewModel.installedCount.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val uiError by viewModel.error.collectAsState()

    val migrationViewModel: com.radiant.webtoon.ui.browse.MigrationViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return com.radiant.webtoon.ui.browse.MigrationViewModel(repository) as T
        }
    })
    val isMigrating by migrationViewModel.isMigrating.collectAsState()

    androidx.compose.runtime.LaunchedEffect(initialQuery, migrateFromMangaId, migrateFromSourceId) {
        if (initialQuery != null) {
            val targetManga = if (migrateFromMangaId != null && migrateFromSourceId != null) {
                repository.mangaDao.getManga(migrateFromMangaId, migrateFromSourceId)
            } else null

            viewModel.setInitialQuery(initialQuery, targetManga)
        }
    }

    var showMigrationDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, com.radiant.webtoon.core.model.Webtoon>?>(null) }

    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    androidx.compose.runtime.LaunchedEffect(migrationViewModel) {
        migrationViewModel.errors.collect { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        }
    }

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
                        placeholder = {
                            Text(if (migrateFromManga != null) "Search other sources..." else "Search pinned sources...")
                        },
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
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
            if (isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            migrateFromManga?.let { from ->
                val fromSourceName = repository.extensionManager.getSource(from.source)?.name ?: "Unavailable source"
                Text(
                    text = "Migrating \"${from.title}\" from $fromSourceName. Pick the matching title below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // FILTER CHIPS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterHasResults,
                    onClick = { viewModel.toggleFilterHasResults() },
                    label = { Text("Has results") }
                )
            }

            val displaySources = searchableSources.filter { source ->
                if (filterHasResults) {
                    val sourceResult = results.find { it.sourceId == source.id }
                    if (sourceResult == null ||
                        sourceResult.webtoons.isEmpty() ||
                        sourceResult.status == SourceSearchStatus.Error ||
                        sourceResult.status == SourceSearchStatus.VerificationNeeded
                    ) {
                        return@filter false
                    }
                }
                true
            }

            if (displaySources.isEmpty()) {
                if (searchableSources.isEmpty() && !isSearching) {
                    if (installedCount == 0) {
                        NoSourcesInstalled(onRefresh = { viewModel.refreshSources() })
                    } else {
                        SearchPlaceholder("No pinned sources. Pin extensions in Browse to search them.")
                    }
                } else if (filterHasResults && !isSearching) {
                    SearchPlaceholder("No results matching filters.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding() + 16.dp)
                ) {
                    itemsIndexed(
                        displaySources.distinctBy { it.id },
                        key = { index, source -> "${source.id}_$index" }
                    ) { _, source ->
                        val sourceResult = results.find { it.sourceId == source.id }
                        val isSourceLoading = sourceResult?.status == SourceSearchStatus.Loading
                        
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

                                if (isSourceLoading) {
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
                                    if (sourceResult.status == SourceSearchStatus.VerificationNeeded) {
                                        SourceInlineChallengeRow(
                                            message = sourceResult.error ?: "Verification needed",
                                            onSolveInWebView = {
                                                val url = sourceResult.challengeUrl
                                                    ?: source.baseUrl.takeIf { it.isNotBlank() }
                                                    ?: return@SourceInlineChallengeRow
                                                onWebViewClick(source.id, url)
                                            },
                                        )
                                    } else if (sourceResult.webtoons.isEmpty() && !isSourceLoading) {
                                        // ── No results / error per source ──
                                        if (sourceResult.status == SourceSearchStatus.Error) {
                                            SourceInlineErrorRow(
                                                message = sourceResult.error ?: "Failed to load",
                                            )
                                        } else {
                                            Text(
                                                text = sourceResult.error ?: "No results found",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    } else if (sourceResult.webtoons.isNotEmpty()) {
                                        // ── Horizontal carousel of manga cards (Mihon-style) ──
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            itemsIndexed(
                                                sourceResult.webtoons.distinctBy {
                                                    it.url?.takeIf { url -> url.isNotBlank() } ?: it.id
                                                },
                                                key = { index, item ->
                                                    "${item.url?.takeIf { it.isNotBlank() } ?: item.id}_$index"
                                                }
                                            ) { _, webtoon ->
                                                Box(modifier = Modifier.width(110.dp)) {
                                                    val webtoonKey = webtoon.url?.takeIf { it.isNotBlank() } ?: webtoon.id
                                                    WebtoonThumbnailCard(
                                                        webtoon = webtoon,
                                                        onClick = {
                                                            if (migrateFromManga != null) {
                                                                showMigrationDialog = source.id to webtoon
                                                            } else {
                                                                onWebtoonClick(source.id, webtoonKey)
                                                            }
                                                        },
                                                        sourceId = source.id,
                                                        compact = true,
                                                        sharedKey = coverSharedKey(source.id, webtoonKey),
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
                onDismissRequest = { if (!isMigrating) showMigrationDialog = null },
                title = { Text("Migrate to this title?") },
                text = {
                    val newSourceName = repository.extensionManager.getSource(targetSourceId)?.name ?: targetSourceId
                    val oldSourceName = migrateFromManga?.source?.let {
                        repository.extensionManager.getSource(it)?.name
                    } ?: "Unavailable source"
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("From: ${migrateFromManga?.title.orEmpty()} ($oldSourceName)")
                        Text("To: ${targetWebtoon.title} ($newSourceName)")
                        Text(
                            "Reading progress, bookmarks and library categories will transfer to the new " +
                                "entry. The old entry will be removed from your library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isMigrating,
                        onClick = {
                            val oldManga = migrateFromManga ?: return@Button
                            showMigrationDialog = null
                            migrationViewModel.migrateWebtoon(oldManga, targetWebtoon, targetSourceId) {
                                val newWebtoonId = targetWebtoon.url?.takeIf { it.isNotBlank() } ?: targetWebtoon.id
                                onMigrationComplete(targetSourceId, newWebtoonId)
                            }
                        },
                    ) {
                        Text("Migrate")
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isMigrating, onClick = { showMigrationDialog = null }) {
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
                        Text("Migrating...")
                    }
                }
            }
        }
    }
}



@Composable
private fun SourceInlineChallengeRow(
    message: String,
    onSolveInWebView: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        TextButton(onClick = onSolveInWebView, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Solve in WebView")
        }
    }
}

@Composable
private fun SourceInlineErrorRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
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
