package com.radiant.webtoon.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    repository: WebtoonRepository,
    onBackClick: () -> Unit
) {
    var chaptersThisWeek by remember { mutableIntStateOf(0) }
    var totalHistory by remember { mutableIntStateOf(0) }
    var libraryCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val history = repository.getHistory()
            totalHistory = history.size
            libraryCount = repository.getLibrary().size
            val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
            chaptersThisWeek = history.count { it.read_at >= weekAgo }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading stats") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("This week", style = MaterialTheme.typography.labelLarge)
                    Text("$chaptersThisWeek chapters", style = MaterialTheme.typography.headlineMedium)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("All-time history entries", style = MaterialTheme.typography.labelLarge)
                    Text("$totalHistory", style = MaterialTheme.typography.headlineMedium)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Library titles", style = MaterialTheme.typography.labelLarge)
                    Text("$libraryCount", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}
