package com.radiant.webtoon.ui.upcoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.radiant.webtoon.core.database.UpcomingRelease
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.UpcomingUpdateWorker
import com.radiant.webtoon.ui.components.listItemRippleClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpcomingViewModel(private val repository: WebtoonRepository) : ViewModel() {
    private val _releases = MutableStateFlow<List<UpcomingRelease>>(emptyList())
    val releases: StateFlow<List<UpcomingRelease>> = _releases

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val app = repository.appContext.applicationContext as com.radiant.webtoon.WebtoonApplication
                WorkManager.getInstance(app).enqueue(
                    OneTimeWorkRequestBuilder<UpcomingUpdateWorker>().build(),
                )
                kotlinx.coroutines.delay(500)
                _releases.value = app.database.upcomingReleaseDao().getAll()
            } finally {
                _isLoading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(
    repository: WebtoonRepository,
    onBackClick: () -> Unit,
    onWebtoonClick: (String, String) -> Unit,
) {
    val viewModel: UpcomingViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return UpcomingViewModel(repository) as T
            }
        },
    )
    val releases by viewModel.releases.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upcoming") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && releases.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                releases.isEmpty() -> {
                    Text(
                        "No upcoming chapters in the next 7 days.\nTap refresh to scan your library.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(releases, key = { it.id }) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .listItemRippleClickable(onClick = {
                                        onWebtoonClick(item.sourceId, item.mangaUrl)
                                    })
                                    .padding(12.dp),
                            ) {
                                Text(item.mangaTitle, style = MaterialTheme.typography.titleMedium)
                                Text(item.chapterName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    dateFormat.format(Date(item.releaseAt)),
                                    style = MaterialTheme.typography.bodySmall,
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
