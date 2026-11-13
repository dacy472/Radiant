package com.radiant.webtoon.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var assignTarget by remember { mutableStateOf<Manga?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
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
                    IconButton(onClick = { showAddCategory = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add category")
                    }
                    DownloadManagerIconButton(onClick = onDownloadQueueClick)
                }
            )
        }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = { viewModel.selectCategory(null) },
                    label = { Text("All") }
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { viewModel.selectCategory(cat.id) },
                        label = { Text(cat.name) }
                    )
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Your library is empty")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { "${it.manga.source}|${it.manga.url}" }) { item ->
                        WebtoonThumbnailCard(
                            webtoon = Webtoon(
                                id = item.manga.url,
                                title = item.manga.title,
                                thumbnail = item.manga.thumbnail_url ?: ""
                            ),
                            onClick = { onWebtoonClick(item.manga.source, item.manga.url) },
                            onLongClick = { assignTarget = item.manga },
                            manga = item.manga,
                            sourceId = item.manga.source,
                            unreadCount = item.unreadCount,
                            downloadedCount = item.downloadedCount,
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
