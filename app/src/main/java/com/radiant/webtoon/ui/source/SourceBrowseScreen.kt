package com.radiant.webtoon.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.ui.components.WebtoonThumbnailCard
import com.radiant.webtoon.ui.components.coverSharedKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    extensionManager: ExtensionManager,
    sourceId: String,
    onWebtoonClick: (String, String) -> Unit,
    onBackClick: () -> Unit,
    onWebViewClick: (String) -> Unit = {},
) {
    val viewModel: SourceBrowseViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return SourceBrowseViewModel(extensionManager, sourceId) as T
        }
    })

    val state by viewModel.state.collectAsState()
    val webtoons by viewModel.webtoons.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val sourceBaseUrl = remember(sourceId, state) { viewModel.getSourceBaseUrl() }
    val gridState = rememberLazyGridState()

    var searchQuery by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            lastVisibleItem.index >= gridState.layoutInfo.totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && state !is BrowseState.Loading && state !is BrowseState.Error) {
            viewModel.loadNextPage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.getSourceName()) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onWebViewClick(sourceBaseUrl) },
                        enabled = sourceBaseUrl.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Public, contentDescription = "Open in WebView")
                    }
                    if (selectedTab == BrowseTab.SEARCH) {
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filters")
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == BrowseTab.POPULAR,
                    onClick = { viewModel.selectTab(BrowseTab.POPULAR) },
                    text = { Text("Popular") }
                )
                if (viewModel.supportsLatest) {
                    Tab(
                        selected = selectedTab == BrowseTab.LATEST,
                        onClick = { viewModel.selectTab(BrowseTab.LATEST) },
                        text = { Text("Latest") }
                    )
                }
                Tab(
                    selected = selectedTab == BrowseTab.SEARCH,
                    onClick = { viewModel.selectTab(BrowseTab.SEARCH) },
                    text = { Text("Search") }
                )
            }

            if (selectedTab == BrowseTab.SEARCH) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search in source...") },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            viewModel.searchSource(searchQuery)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        })
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            viewModel.searchSource("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    } else {
                        IconButton(onClick = {
                            viewModel.searchSource(searchQuery)
                            keyboardController?.hide()
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state is BrowseState.Loading && webtoons.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state is BrowseState.Error && webtoons.isEmpty() -> {
                        val errorState = state as BrowseState.Error
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (errorState.isCloudflareChallenge) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = errorState.message,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                val challengeUrl = errorState.challengeUrl
                                if (!challengeUrl.isNullOrBlank()) {
                                    Button(onClick = { onWebViewClick(challengeUrl) }) {
                                        Text("Solve in WebView")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            } else {
                                Text(
                                    text = errorState.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                            OutlinedButton(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                        }
                    }
                    state is BrowseState.Success && webtoons.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (selectedTab == BrowseTab.SEARCH) {
                                    "No results found. Try fewer or different words."
                                } else {
                                    "No titles found"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(108.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 8.dp,
                                bottom = 8.dp + padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                webtoons.distinctBy { it.identityKey() },
                                key = { index, item -> "${item.identityKey()}_$index" }
                            ) { _, webtoon ->
                                val webtoonKey = webtoon.url ?: webtoon.id
                                WebtoonThumbnailCard(
                                    webtoon = webtoon,
                                    onClick = { onWebtoonClick(sourceId, webtoonKey) },
                                    sourceId = sourceId,
                                    compact = true,
                                    sharedKey = coverSharedKey(sourceId, webtoonKey),
                                )
                            }

                            if (state is BrowseState.Loading && webtoons.isNotEmpty()) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        SourceFilterSheet(
            filters = viewModel.currentFilters(),
            onDismiss = { showFilters = false },
            onApply = { viewModel.applyFilters(it) }
        )
    }
}
