package com.radiant.webtoon.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val nsfwFilter by settingsManager.nsfwFilter.collectAsState(initial = true)
    val readingDirection by settingsManager.readingDirection.collectAsState(initial = "vertical")
    val themeMode by settingsManager.themeMode.collectAsState(initial = "system")

    var tapCount by remember { mutableIntStateOf(0) }
    var isDeveloperMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            // Reading Settings
            Text("Reading", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Reading direction
            Text("Reading Direction", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val directions = listOf("vertical" to "Vertical", "ltr" to "Left to Right", "rtl" to "Right to Left")
                directions.forEach { (value, label) ->
                    FilterChip(
                        selected = readingDirection == value,
                        onClick = {
                            scope.launch { settingsManager.setReadingDirection(value) }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Content Filtering
            Text("Content", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Filter NSFW", style = MaterialTheme.typography.bodyMedium)
                    Text("Hide adult-only extensions", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = nsfwFilter,
                    onCheckedChange = {
                        scope.launch { settingsManager.setNsfwFilter(it) }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Troubleshooting
            Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
            var cacheCleared by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    // Clear Coil image cache
                    val imageLoader = coil.ImageLoader.Builder(context).build()
                    imageLoader.diskCache?.clear()
                    imageLoader.memoryCache?.clear()
                    cacheCleared = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(if (cacheCleared) "Cache Cleared ✓" else "Clear Image Cache", color = MaterialTheme.colorScheme.onErrorContainer)
            }

            if (isDeveloperMode) {
                Spacer(modifier = Modifier.height(8.dp))
                var extCacheCleared by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        // Clear extension download cache
                        val cacheDir = java.io.File(context.cacheDir, "extension_apks")
                        if (cacheDir.exists()) cacheDir.deleteRecursively()
                        extCacheCleared = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (extCacheCleared) "Extension Cache Cleared ✓" else "Clear Extension Cache", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Version 2.0.0 (Local Extensions)",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .clickable {
                        tapCount++
                        if (tapCount >= 7) {
                            isDeveloperMode = true
                        }
                    },
                textAlign = TextAlign.Center
            )
        }
    }
}
