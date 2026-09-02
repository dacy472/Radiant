package com.radiant.webtoon.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.radiant.webtoon.BuildConfig
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBackClick: () -> Unit,
    onPageListDebugClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val nsfwFilter by settingsManager.nsfwFilter.collectAsState(initial = true)
    val readingDirection by settingsManager.readingDirection.collectAsState(initial = "vertical")
    val themeMode by settingsManager.themeMode.collectAsState(initial = "system")

    var tapCount by remember { mutableIntStateOf(0) }
    var isDeveloperMode by remember { mutableStateOf(false) }

    val extensionAdvancedMode by settingsManager.extensionAdvancedMode.collectAsState(initial = false)
    val autoUpdateExtensions by settingsManager.autoUpdateExtensions.collectAsState(initial = true)
    val pageListDebugMode by settingsManager.pageListDebugMode.collectAsState(initial = false)
    val storedCustomUserAgent by settingsManager.customUserAgent.collectAsState(initial = "")
    var customUserAgentInput by remember { mutableStateOf("") }
    LaunchedEffect(storedCustomUserAgent) {
        customUserAgentInput = storedCustomUserAgent
    }
    val webViewUserAgent = remember {
        eu.kanade.tachiyomi.network.WebViewUserAgentProvider.getDefault(context)
    }
    var showAdvancedWarning by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
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

            Text("Extensions", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Install any extension", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Opens the full English/all-lang Keiyoushi catalog. Uses more memory; some sources are untested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = extensionAdvancedMode,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showAdvancedWarning = true
                        } else {
                            scope.launch { settingsManager.setExtensionAdvancedMode(false) }
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Update Extensions", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "When on, verified channel updates download and apply in the background. " +
                            "When off, you'll see an update badge and can install manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoUpdateExtensions,
                    onCheckedChange = {
                        scope.launch { settingsManager.setAutoUpdateExtensions(it) }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Network", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Custom User-Agent", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Leave blank to use this device's WebView User-Agent (matches Cloudflare bypass).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = customUserAgentInput,
                onValueChange = { value ->
                    customUserAgentInput = value
                    scope.launch { settingsManager.setCustomUserAgent(value) }
                },
                label = { Text("Custom User-Agent") },
                placeholder = {
                    Text(
                        webViewUserAgent,
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                minLines = 2,
                maxLines = 4,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
            var cacheCleared by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    // Clear the app-wide Coil ImageLoader cache
                    val imageLoader = coil.Coil.imageLoader(context)
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

                Spacer(modifier = Modifier.height(8.dp))
                val app = context.applicationContext as com.radiant.webtoon.WebtoonApplication
                var showResetConfirm by remember { mutableStateOf(false) }
                var resetInProgress by remember { mutableStateOf(false) }
                var resetMessage by remember { mutableStateOf<String?>(null) }

                Button(
                    onClick = { showResetConfirm = true },
                    enabled = !resetInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (resetInProgress) "Resetting Extensions…" else "Reset Extensions",
                        color = MaterialTheme.colorScheme.onError,
                    )
                }

                resetMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { if (!resetInProgress) showResetConfirm = false },
                        title = { Text("Reset all extensions?") },
                        text = {
                            Text(
                                "This deletes every locally downloaded extension APK, clears the " +
                                    "extension database, and reinstalls curated defaults (including " +
                                    "Flame Comics) from the Keiyoushi repository.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    resetInProgress = true
                                    resetMessage = null
                                    scope.launch {
                                        try {
                                            val result = app.extensionManager.forceResetExtensions()
                                            resetMessage =
                                                "Reset complete. Removed ${result.deletedApkCount} APK(s); " +
                                                    "${result.reinstalledCount} extension(s) reinstalled from repo."
                                        } catch (e: Exception) {
                                            resetMessage = "Reset failed: ${e.localizedMessage ?: e.message}"
                                        } finally {
                                            resetInProgress = false
                                            showResetConfirm = false
                                        }
                                    }
                                },
                                enabled = !resetInProgress,
                            ) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showResetConfirm = false },
                                enabled = !resetInProgress,
                            ) { Text("Cancel") }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Page List Debug", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable page-list debug", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Log raw HTTP/parse failures when chapters return zero pages. " +
                                "Shows a debug overlay in the reader.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = pageListDebugMode,
                        onCheckedChange = {
                            scope.launch { settingsManager.setPageListDebugMode(it) }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onPageListDebugClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View page-list debug logs")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (Local Extensions)",
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

    if (showAdvancedWarning) {
        AlertDialog(
            onDismissRequest = { showAdvancedWarning = false },
            title = { Text("Enable advanced extensions?") },
            text = {
                Text(
                    "You can install extensions outside Radiant's curated list. " +
                        "This may increase memory use and expose NSFW sources if filtering is disabled.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settingsManager.setExtensionAdvancedMode(true) }
                        showAdvancedWarning = false
                    },
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showAdvancedWarning = false }) { Text("Cancel") }
            },
        )
    }
}
