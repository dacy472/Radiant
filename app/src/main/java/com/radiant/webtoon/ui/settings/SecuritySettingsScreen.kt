package com.radiant.webtoon.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.WebtoonApplication
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { (context.applicationContext as WebtoonApplication).settingsManager }
    val scope = rememberCoroutineScope()

    val requireBiometrics by settingsManager.requireBiometrics.collectAsState(initial = false)
    val incognitoMode by settingsManager.incognitoMode.collectAsState(initial = false)
    val flagSecure by settingsManager.flagSecure.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            SecurityToggleRow(
                title = "App Lock",
                summary = "Require fingerprint/face unlock to open app",
                checked = requireBiometrics,
                onCheckedChange = { scope.launch { settingsManager.setRequireBiometrics(it) } }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SecurityToggleRow(
                title = "Incognito mode",
                summary = "Do not record reading history while enabled",
                checked = incognitoMode,
                onCheckedChange = { scope.launch { settingsManager.setIncognito(it) } }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SecurityToggleRow(
                title = "Secure screen",
                summary = "Block screenshots and screen recording (FLAG_SECURE)",
                checked = flagSecure,
                onCheckedChange = { scope.launch { settingsManager.setFlagSecure(it) } }
            )
        }
    }
}

@Composable
private fun SecurityToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
