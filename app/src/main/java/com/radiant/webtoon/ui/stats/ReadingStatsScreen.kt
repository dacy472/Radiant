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
    var readingStreak by remember { mutableIntStateOf(0) }
    var weeklyCounts by remember { mutableStateOf(List(7) { 0 }) }
    var perSourceCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val history = repository.getHistory()
            totalHistory = history.size
            libraryCount = repository.getLibrary().size
            val now = Calendar.getInstance()
            val weekAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
            chaptersThisWeek = history.count { it.read_at >= weekAgo }

            val dayBuckets = MutableList(7) { 0 }
            for (i in 0 until 7) {
                val dayStart = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -i)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val dayEnd = dayStart + 24 * 60 * 60 * 1000
                dayBuckets[6 - i] = history.count { it.read_at in dayStart until dayEnd }
            }
            weeklyCounts = dayBuckets

            var streak = 0
            var cursor = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            while (true) {
                val start = cursor.timeInMillis
                val end = start + 24 * 60 * 60 * 1000
                if (history.any { it.read_at in start until end }) {
                    streak++
                    cursor.add(Calendar.DAY_OF_YEAR, -1)
                } else break
            }
            readingStreak = streak

            perSourceCounts = history
                .groupBy { it.source_id }
                .map { (source, items) -> source to items.size }
                .sortedByDescending { it.second }
                .take(5)
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
                    Text("Streak: $readingStreak day(s)", style = MaterialTheme.typography.bodyMedium)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last 7 days", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                    ) {
                        weeklyCounts.forEach { count ->
                            val height = (count.coerceAtMost(10) * 8 + 4).dp
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(height)
                                    .padding(horizontal = 2.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small,
                                ) {}
                            }
                        }
                    }
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
            if (perSourceCounts.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Top sources", style = MaterialTheme.typography.labelLarge)
                        perSourceCounts.forEach { (source, count) ->
                            Text("$source — $count chapters", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
