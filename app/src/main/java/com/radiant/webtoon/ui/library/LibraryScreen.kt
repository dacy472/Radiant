package com.radiant.webtoon.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Queue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.database.WebtoonDatabase
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.ui.components.WebtoonThumbnailCard
import com.radiant.webtoon.ui.components.bounceClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

import com.radiant.webtoon.core.repository.WebtoonRepository
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(private val webtoonDao: com.radiant.webtoon.core.database.WebtoonDao) : ViewModel() {
    val libraryItems: StateFlow<List<com.radiant.webtoon.core.database.WebtoonEntity>> = 
        webtoonDao.getFavorites().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: com.radiant.webtoon.core.repository.WebtoonRepository,
    onWebtoonClick: (String, String) -> Unit,
    onUpdatesClick: () -> Unit
) {
    val context = LocalContext.current
    val webtoonDao = com.radiant.webtoon.core.database.AppDatabase.getDatabase(context).webtoonDao()
    val viewModel: LibraryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(webtoonDao) as T
        }
    })
    val items by viewModel.libraryItems.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Library") }
            ) 
        }
    ) { paddingValues ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Your library is empty")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.padding(paddingValues).padding(8.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(items, key = { it.url }) { item ->
                    WebtoonThumbnailCard(
                        webtoon = Webtoon(id = item.url, title = item.title, thumbnail = item.thumbnailUrl),
                        onClick = { 
                            onWebtoonClick(item.sourceId, item.url) 
                        }
                    )
                }
            }
        }
    }
}
