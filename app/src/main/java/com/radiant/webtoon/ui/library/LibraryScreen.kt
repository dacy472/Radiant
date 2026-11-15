package com.radiant.webtoon.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Scale
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import com.radiant.webtoon.WebtoonApplication
import com.radiant.webtoon.ui.components.AppMotion
import com.radiant.webtoon.ui.components.coverSharedKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radiant.webtoon.core.database.Category
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.ui.components.DownloadManagerIconButton
import com.radiant.webtoon.ui.components.SpringAlertDialog
import com.radiant.webtoon.ui.components.WebtoonThumbnailCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibrarySort { TITLE, DATE_ADDED, UNREAD }
enum class LibraryFilter { ALL, UNREAD, DOWNLOADED, STARTED }

data class LibraryDisplayItem(
    val manga: Manga,
    val unreadCount: Int = 0,
    val downloadedCount: Int = 0,
    val isDownloaded: Boolean = false,
    val hasHistory: Boolean = false,
)

data class ContinueReading(
    val manga: Manga,
    val chapterTitle: String,
    val readAt: Long,
)

class LibraryViewModel(private val repository: WebtoonRepository) : ViewModel() {
    private val _sort = MutableStateFlow(LibrarySort.TITLE)
    val sort: StateFlow<LibrarySort> = _sort

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter

    private val _selectedCategoryId = MutableStateFlow<Int?>(null)
    val selectedCategoryId: StateFlow<Int?> = _selectedCategoryId

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    private val _displayItems = MutableStateFlow<List<LibraryDisplayItem>>(emptyList())
    private val _categoryMappings = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    private val _continueReading = MutableStateFlow<List<ContinueReading>>(emptyList())
    val continueReading: StateFlow<List<ContinueReading>> = _continueReading

    val libraryItems: StateFlow<List<LibraryDisplayItem>> = combine(
        _displayItems, _sort, _filter, _selectedCategoryId, _categoryMappings
    ) { items, sort, filter, categoryId, mappings ->
        var result = items
        if (categoryId != null) {
            result = result.filter {
                mappings["${it.manga.source}|${it.manga.url}"]?.contains(categoryId) == true
            }
        }
        result = when (filter) {
            LibraryFilter.ALL -> result
            LibraryFilter.UNREAD -> result.filter { it.unreadCount > 0 }
            LibraryFilter.DOWNLOADED -> result.filter { it.isDownloaded }
            LibraryFilter.STARTED -> result.filter { it.hasHistory }
        }
        when (sort) {
            LibrarySort.TITLE -> result.sortedBy { it.manga.title.lowercase() }
            LibrarySort.DATE_ADDED -> result.sortedByDescending { it.manga.date_added }
            LibrarySort.UNREAD -> result.sortedByDescending { it.unreadCount }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { refreshCategories() }
        viewModelScope.launch {
            combine(
                repository.mangaDao.getFavoritesFlow(),
                repository.downloadedChaptersFlow(),
                repository.allChaptersFlow(),
            ) { favorites, downloads, chapters ->
                Triple(favorites, downloads, chapters)
            }.collect { (favorites, downloads, chapters) ->
                enrich(favorites, downloads, chapters)
            }
        }
    }

    private suspend fun enrich(
        favorites: List<Manga>,
        downloads: List<com.radiant.webtoon.core.database.DownloadedChapter> = emptyList(),
        allChapters: List<com.radiant.webtoon.core.database.Chapter>? = null,
    ) {
        val downloadCounts = downloads.groupingBy { "${it.sourceId}|${it.webtoonId}" }.eachCount()
        val history = runCatching { repository.getHistory() }.getOrDefault(emptyList())
        val historyKeys = history.map { "${it.source_id}|${it.webtoon_id}" }.toSet()
        val latestBySeries = history.groupBy { "${it.source_id}|${it.webtoon_id}" }
            .mapValues { (_, rows) -> rows.maxBy { it.read_at } }
        _continueReading.value = favorites.mapNotNull { manga ->
            val latest = latestBySeries["${manga.source}|${manga.url}"] ?: return@mapNotNull null
            ContinueReading(
                manga = manga,
                chapterTitle = latest.chapter_title.ifBlank { "Continue" },
                readAt = latest.read_at,
            )
        }.sortedByDescending { it.readAt }.take(12)
        val chaptersByManga = allChapters?.groupBy { it.source to it.manga_id }

        _displayItems.value = favorites.map { manga ->
            val key = "${manga.source}|${manga.url}"
            val chapters = chaptersByManga?.get(manga.source to manga.url)
                ?: repository.getChaptersForManga(manga.url, manga.source)
            val downloadedCount = downloadCounts[key] ?: 0
            LibraryDisplayItem(
                manga = manga,
                unreadCount = chapters.count { !it.read },
                downloadedCount = downloadedCount,
                isDownloaded = downloadedCount > 0,
                hasHistory = historyKeys.contains(key),
            )
        }

        val map = mutableMapOf<String, Set<Int>>()
        for (manga in favorites) {
            val refs = repository.getCategoryMappings(manga.url, manga.source)
            map["${manga.source}|${manga.url}"] = refs.map { it.categoryId }.toSet()
        }
        _categoryMappings.value = map
    }

    suspend fun refreshCategories() {
        _categories.value = repository.getCategories()
    }

    fun setSort(sort: LibrarySort) { _sort.value = sort }
    fun setFilter(filter: LibraryFilter) { _filter.value = filter }
    fun selectCategory(id: Int?) { _selectedCategoryId.value = id }

    fun createCategory(name: String) {
        viewModelScope.launch {
            repository.insertCategory(Category(name = name, order = _categories.value.size))
            refreshCategories()
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            if (_selectedCategoryId.value == category.id) _selectedCategoryId.value = null
            refreshCategories()
        }
    }

    fun assignToCategory(manga: Manga, categoryId: Int) {
        viewModelScope.launch {
            repository.assignMangaToCategory(manga.url, manga.source, categoryId)
            enrich(repository.getLibrary(), repository.downloadedChaptersFlow().first())
        }
    }
}

class LibraryViewModelFactory(private val repository: WebtoonRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LibraryViewModel(repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: WebtoonRepository,
    onWebtoonClick: (String, String) -> Unit,
    onUpdatesClick: () -> Unit,
    onDownloadQueueClick: () -> Unit = {},
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository))
    val items by viewModel.libraryItems.collectAsStateWithLifecycle()
    val continueReading by viewModel.continueReading.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val settingsManager = remember { (context.applicationContext as WebtoonApplication).settingsManager }
    val displayMode by settingsManager.libraryDisplayMode.collectAsStateWithLifecycle(initialValue = "compact")
    val compactGrid = displayMode != "comfortable"
    val scope = rememberCoroutineScope()

    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var assignTarget by remember { mutableStateOf<Manga?>(null) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Library")
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ) {
                                AnimatedContent(
                                    targetState = items.size,
                                    transitionSpec = {
                                        (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                                    },
                                    label = "libraryCount",
                                ) { count -> Text(count.toString()) }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filter")
                        }
                        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                            LibraryFilter.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { viewModel.setFilter(f); showFilterMenu = false },
                                    trailingIcon = { if (filter == f) Text("✓") }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (s) {
                                                LibrarySort.TITLE -> "Title"
                                                LibrarySort.DATE_ADDED -> "Date added"
                                                LibrarySort.UNREAD -> "Unread"
                                            }
                                        )
                                    },
                                    onClick = { viewModel.setSort(s); showSortMenu = false },
                                    trailingIcon = { if (sort == s) Text("✓") }
                                )
                            }
                        }
                    }
                    DownloadManagerIconButton(onClick = onDownloadQueueClick)
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text(if (compactGrid) "Comfortable grid" else "Compact grid") },
                                leadingIcon = {
                                    Icon(
                                        if (compactGrid) Icons.Default.ViewAgenda else Icons.Default.GridView,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    scope.launch {
                                        settingsManager.setLibraryDisplayMode(if (compactGrid) "comfortable" else "compact")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("New category") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    showAddCategory = true
                                },
                            )
                        }
                    }
                }
            )
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.setFilter(option) },
                        label = { Text(libraryFilterLabel(option)) },
                        colors = chipColors,
                    )
                }
            }
            if (categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text("All") },
                        colors = chipColors,
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = { viewModel.selectCategory(cat.id) },
                            label = { Text(cat.name) },
                            colors = chipColors,
                        )
                    }
                }
            }
            }
        }
    ) { paddingValues ->
        Crossfade(
            targetState = items.isEmpty(),
            animationSpec = tween(220),
            label = "libraryEmpty",
            modifier = Modifier.fillMaxSize(),
        ) { empty ->
            if (empty) {
                LibraryEmptyState(
                    filtered = filter != LibraryFilter.ALL || selectedCategoryId != null,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = paddingValues.calculateTopPadding() + 4.dp,
                        bottom = paddingValues.calculateBottomPadding() + 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactGrid) 8.dp else 4.dp)
                ) {
                    val visibleKeys = items.map { "${it.manga.source}|${it.manga.url}" }.toSet()
                    val continueVisible = continueReading.filter {
                        "${it.manga.source}|${it.manga.url}" in visibleKeys
                    }
                    if (continueVisible.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                            ContinueRail(
                                items = continueVisible,
                                onOpen = { onWebtoonClick(it.manga.source, it.manga.url) },
                            )
                        }
                    }
                    items(items, key = { "${it.manga.source}|${it.manga.url}" }) { item ->
                        WebtoonThumbnailCard(
                            webtoon = Webtoon(
                                id = item.manga.url,
                                title = item.manga.title,
                                thumbnail = item.manga.thumbnail_url ?: ""
                            ),
                            onClick = { onWebtoonClick(item.manga.source, item.manga.url) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = AppMotion.spatial(),
                                fadeOutSpec = tween(160),
                            ),
                            onLongClick = { assignTarget = item.manga },
                            manga = item.manga,
                            sourceId = item.manga.source,
                            unreadCount = item.unreadCount,
                            downloadedCount = item.downloadedCount,
                            compact = compactGrid,
                            sharedKey = coverSharedKey(item.manga.source, item.manga.url),
                        )
                    }
                }
            }
        }
    }

    if (showAddCategory) {
        SpringAlertDialog(
            onDismissRequest = { showAddCategory = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    placeholder = { Text("Category name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCategoryName.isNotBlank()) {
                        viewModel.createCategory(newCategoryName.trim())
                        newCategoryName = ""
                        showAddCategory = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategory = false }) { Text("Cancel") }
            },
        )
    }

    assignTarget?.let { manga ->
        SpringAlertDialog(
            onDismissRequest = { assignTarget = null },
            title = { Text("Assign category") },
            text = {
                Column {
                    if (categories.isEmpty()) {
                        Text("No categories yet. Create one first.")
                    } else {
                        categories.forEach { cat ->
                            TextButton(onClick = {
                                viewModel.assignToCategory(manga, cat.id)
                                assignTarget = null
                            }) { Text(cat.name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assignTarget = null }) { Text("Close") }
            },
        )
    }
}

private fun libraryFilterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ALL -> "All"
    LibraryFilter.UNREAD -> "Unread"
    LibraryFilter.DOWNLOADED -> "Downloaded"
    LibraryFilter.STARTED -> "Started"
}

@Composable
private fun ContinueRail(
    items: List<ContinueReading>,
    onOpen: (ContinueReading) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = "CONTINUE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                ContinueCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun ContinueCard(
    item: ContinueReading,
    onClick: () -> Unit,
) {
    val headers = rememberSourceImageHeaders(manga = item.manga, sourceId = item.manga.source)
    Column(Modifier.width(168.dp).clickable { onClick() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            RadiantAsyncImage(
                url = item.manga.thumbnail_url.orEmpty(),
                contentDescription = item.manga.title,
                modifier = Modifier.fillMaxSize(),
                headers = headers,
                manga = item.manga,
                sourceId = item.manga.source,
                widthDp = 336,
                heightDp = 184,
                precision = coil.size.Precision.INEXACT,
                scale = Scale.FILL,
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                        ),
                    ),
            )
            Text(
                text = item.manga.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
        Text(
            text = item.chapterTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun LibraryEmptyState(filtered: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (filtered) "Nothing matches this view" else "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (filtered) {
                "Try another category or clear the filter."
            } else {
                "Add series from Browse to see them here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
