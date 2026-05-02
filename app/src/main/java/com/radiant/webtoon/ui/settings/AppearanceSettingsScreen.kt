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
fun AppearanceSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val themeMode by settingsManager.themeMode.collectAsState(initial = "system")
    val dateFormat by settingsManager.dateFormat.collectAsState(initial = "MM/dd/yyyy")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            // Theme Mode
            Text("Theme Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val themes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                themes.forEach { (value, label) ->
                    FilterChip(
                        selected = themeMode == value,
                        onClick = { scope.launch { settingsManager.setThemeMode(value) } },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Date Format
            Text("Date Format", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val formats = listOf("MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd")
                formats.forEach { format ->
                    FilterChip(
                        selected = dateFormat == format,
                        onClick = { scope.launch { settingsManager.setDateFormat(format) } },
                        label = { Text(format, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}
