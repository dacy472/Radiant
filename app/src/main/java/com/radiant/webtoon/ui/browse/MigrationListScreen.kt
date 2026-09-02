package com.radiant.webtoon.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radiant.webtoon.core.database.Manga
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.ui.components.CoverErrorPlaceholder
import com.radiant.webtoon.ui.components.RadiantAsyncImage
import com.radiant.webtoon.ui.components.rememberSourceImageHeaders
import coil.size.Scale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationListScreen(
    sourceId: String,
    repository: WebtoonRepository,
    onBackClick: () -> Unit,
    onMangaClick: (String, String) -> Unit // mangaId, title
) {
    val viewModel: MigrationViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return MigrationViewModel(repository) as T
        }
    })

    LaunchedEffect(sourceId) {
        viewModel.loadMangasBySource(sourceId)
    }

    val mangas by viewModel.mangasToMigrate.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Comic to Migrate") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (mangas.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("No library items found for this source.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(
                    mangas.distinctBy { it.url },
                    key = { index, manga -> "${manga.url}_$index" }
                ) { _, manga ->
                    MigrationMangaCard(manga = manga, onClick = { onMangaClick(manga.url, manga.title) })
                }
            }
        }
    }
}

@Composable
fun MigrationMangaCard(manga: Manga, onClick: () -> Unit) {
    val coverHeaders = rememberSourceImageHeaders(manga = manga)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            RadiantAsyncImage(
                url = manga.thumbnail_url.orEmpty(),
                contentDescription = null,
                headers = coverHeaders,
                manga = manga,
                scale = Scale.FILL,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                error = { CoverErrorPlaceholder(Modifier.fillMaxSize()) },
            )
            Text(
                text = manga.title,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
