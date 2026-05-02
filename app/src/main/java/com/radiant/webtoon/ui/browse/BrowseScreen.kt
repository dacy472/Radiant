package com.radiant.webtoon.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.Source
import com.radiant.webtoon.ui.extensions.ExtensionsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    extensionManager: ExtensionManager,
    onWebtoonClick: (String, String) -> Unit,
    onBrowseClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onWebViewClick: (String) -> Unit,
    onMigrateSourceClick: (String) -> Unit
) {
    val browseViewModel: BrowseViewModel = viewModel()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val tabs = listOf("Sources", "Extensions", "Migrate")

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                        } else {
                            Text("Browse")
                        }
                    },
                    actions = {
                        if (isSearching) {
                            IconButton(onClick = {
                                searchQuery = ""
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search")
                            }
                        } else {
                            when (pagerState.currentPage) {
                                0 -> { // Sources Tab
                                    IconButton(onClick = { isSearching = true }) {
                                        Icon(Icons.Default.Search, contentDescription = "Local Search")
                                    }
                                    IconButton(onClick = onSearchClick) {
                                        Icon(Icons.Default.Public, contentDescription = "Global Search")
                                    }
                                }
                                1 -> { // Extensions Tab
                                    IconButton(onClick = { isSearching = true }) {
                                        Icon(Icons.Default.Search, contentDescription = "Search Extensions")
                                    }
                                    IconButton(onClick = { /* TODO: Trigger Refresh */ }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                }
                                2 -> { // Migrate Tab
                                }
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { page ->
            when (page) {
                0 -> SourcesTab(extensionManager, searchQuery, onBrowseClick, onWebViewClick)
                1 -> ExtensionsTab(extensionManager, searchQuery)
                2 -> MigrateTab(browseViewModel, onMigrateSourceClick)
            }
        }
    }
}

@Composable
fun SourcesTab(extensionManager: ExtensionManager, searchQuery: String, onBrowseClick: (String) -> Unit, onWebViewClick: (String) -> Unit) {
    val sources by extensionManager.installedExtensions.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    
    val filteredSources = sources.filter { it.name.contains(searchQuery, ignoreCase = true) }

    val pinnedSources = filteredSources.filter { it.isPinned }
    val otherSources = filteredSources.filter { !it.isPinned }

    if (sources.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No sources installed.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (pinnedSources.isNotEmpty()) {
            item {
                Text(
                    text = "Pinned",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                )
            }
            items(pinnedSources, key = { it.id }) { source ->
                SourceListItem(
                    source = source,
                    isPinned = true,
                    onPinClick = {
                        coroutineScope.launch { extensionManager.togglePin(source.id) }
                    },
                    onClick = { onBrowseClick(source.id) },
                    onWebViewClick = { onWebViewClick(source.baseUrl) }
                )
            }
        }

        if (otherSources.isNotEmpty()) {
            item {
                Text(
                    text = "All",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                )
            }
            items(otherSources, key = { it.id }) { source ->
                SourceListItem(
                    source = source,
                    isPinned = false,
                    onPinClick = {
                        coroutineScope.launch { extensionManager.togglePin(source.id) }
                    },
                    onClick = { onBrowseClick(source.id) },
                    onWebViewClick = { onWebViewClick(source.baseUrl) }
                )
            }
        }
    }
}

@Composable
fun SourceListItem(
    source: Source,
    isPinned: Boolean,
    onPinClick: () -> Unit,
    onClick: () -> Unit,
    onWebViewClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon — Extension icon on the left, with rounded corners and subtle border
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(source.icon)
                    .crossfade(true)
                    .build(),
                placeholder = rememberVectorPainter(Icons.Default.Extension),
                error = rememberVectorPainter(Icons.Default.Extension),
                fallback = rememberVectorPainter(Icons.Default.Extension),
                contentDescription = "${source.name} icon",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text — Source name and language
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = source.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Actions — Globe icon (WebView) and Pin icon on the far right
        IconButton(onClick = onWebViewClick) {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = "Open in WebView",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPinClick) {
            Icon(
                imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                contentDescription = "Pin Source",
                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun ExtensionsTab(extensionManager: ExtensionManager, searchQuery: String) {
    // We integrate ExtensionsScreen natively.
    ExtensionsScreen(
        extensionManager = extensionManager,
        searchQuery = searchQuery,
        onSearchClick = {},
        onBrowseClick = {}
    )
}

@Composable
fun MigrateTab(viewModel: BrowseViewModel, onMigrateSourceClick: (String) -> Unit) {
    val items by viewModel.migrateItems.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No library entries found.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Add titles to your library to use Migrate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Sources with library entries",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            )
        }
        items(items, key = { it.source.id }) { item ->
            MigrateSourceItem(
                item = item,
                onClick = { onMigrateSourceClick(item.source.id) }
            )
        }
    }
}

@Composable
fun MigrateSourceItem(
    item: MigrateItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Source icon — reuses the same style as SourceListItem
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.source.icon)
                    .crossfade(true)
                    .build(),
                placeholder = rememberVectorPainter(Icons.Default.Extension),
                error = rememberVectorPainter(Icons.Default.Extension),
                fallback = rememberVectorPainter(Icons.Default.Extension),
                contentDescription = "${item.source.name} icon",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Source name + language
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.source.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.source.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Count badge on the far right
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${item.libraryCount} title${if (item.libraryCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}
