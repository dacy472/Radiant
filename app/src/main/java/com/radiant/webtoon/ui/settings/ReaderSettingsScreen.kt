package com.radiant.webtoon.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val readingDirection by settingsManager.readingDirection.collectAsState(initial = "vertical")
    val keepScreenOn by settingsManager.keepScreenOn.collectAsState(initial = true)
    val volumeKeysNav by settingsManager.volumeKeysNav.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            // Reading direction
            Text("Reading Direction", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val directions = listOf("vertical" to "Vertical", "ltr" to "Left to Right", "rtl" to "Right to Left")
                directions.forEach { (value, label) ->
                    FilterChip(
                        selected = readingDirection == value,
                        onClick = { scope.launch { settingsManager.setReadingDirection(value) } },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Keep Screen On
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("Keep Screen On", style = MaterialTheme.typography.titleMedium)
                    Text("Prevent the device from sleeping while reading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = { scope.launch { settingsManager.setKeepScreenOn(it) } }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Volume Keys Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("Volume Keys Navigation", style = MaterialTheme.typography.titleMedium)
                    Text("Use volume keys to turn pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = volumeKeysNav,
                    onCheckedChange = { scope.launch { settingsManager.setVolumeKeysNav(it) } }
                )
            }
        }
    }
}
