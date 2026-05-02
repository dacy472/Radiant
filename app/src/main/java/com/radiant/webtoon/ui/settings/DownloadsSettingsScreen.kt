package com.radiant.webtoon.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val downloadOnlyOnWifi by settingsManager.downloadOnlyOnWifi.collectAsState(initial = false)
    val autoDeleteRead by settingsManager.autoDeleteRead.collectAsState(initial = false)
    val downloadLocationName by settingsManager.downloadLocationName.collectAsState(initial = null)

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val name = docFile?.name ?: "Custom Folder"
            
            scope.launch {
                settingsManager.setDownloadLocationUri(uri.toString())
                settingsManager.setDownloadLocationName(name)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            // Download Location
            ListItem(
                headlineContent = { Text("Download Location") },
                supportingContent = { Text(downloadLocationName ?: "App Internal Storage") },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { folderPickerLauncher.launch(null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Download Only On WiFi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download over Wi-Fi only", style = MaterialTheme.typography.titleMedium)
                    Text("Pause downloads on mobile data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = downloadOnlyOnWifi,
                    onCheckedChange = { scope.launch { settingsManager.setDownloadOnlyOnWifi(it) } }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Auto-delete after reading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-delete read chapters", style = MaterialTheme.typography.titleMedium)
                    Text("Delete downloaded chapters after you finish reading them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoDeleteRead,
                    onCheckedChange = { scope.launch { settingsManager.setAutoDeleteRead(it) } }
                )
            }
        }
    }
}
