package com.radiant.webtoon.ui.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.radiant.webtoon.core.source.BaseUrlResolver
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.TachiyomiSourceAdapter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsScreen(
    sourceId: String,
    extensionManager: ExtensionManager,
    onBackClick: () -> Unit,
) {
    val sourceName = extensionManager.getSourceName(sourceId) ?: "Source"
    val configurable = extensionManager.getConfigurableSource(sourceId)
    val defaultBaseUrl = extensionManager.getDefaultBaseUrl(sourceId)
    val savedOverride = extensionManager.getBaseUrlOverride(sourceId).orEmpty()
    var overrideText by remember(sourceId, savedOverride) { mutableStateOf(savedOverride) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val showBaseUrlOverride = defaultBaseUrl.isNotBlank() &&
        extensionManager.getSource(sourceId) is TachiyomiSourceAdapter

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$sourceName Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showBaseUrlOverride) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Advanced",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Base URL override",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Replace the extension's default domain with a working mirror or proxy. " +
                            "Leave blank to use the default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = overrideText,
                        onValueChange = {
                            overrideText = it
                            validationError = null
                            saveMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Override URL") },
                        placeholder = { Text(defaultBaseUrl) },
                        singleLine = true,
                        isError = validationError != null,
                        supportingText = {
                            when {
                                validationError != null -> Text(validationError!!)
                                saveMessage != null -> Text(saveMessage!!)
                                else -> Text("Default: $defaultBaseUrl")
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val trimmed = overrideText.trim()
                                if (trimmed.isNotEmpty() && !BaseUrlResolver.isValidOverride(trimmed)) {
                                    validationError = "Enter a valid http or https URL"
                                    return@Button
                                }
                                scope.launch {
                                    extensionManager.setBaseUrlOverride(sourceId, trimmed.ifBlank { null })
                                    saveMessage = if (trimmed.isBlank()) {
                                        "Using default base URL"
                                    } else {
                                        "Saved override"
                                    }
                                    validationError = null
                                }
                            },
                        ) {
                            Text("Save")
                        }
                        TextButton(
                            onClick = {
                                overrideText = ""
                                scope.launch {
                                    extensionManager.setBaseUrlOverride(sourceId, null)
                                    saveMessage = "Override cleared"
                                    validationError = null
                                }
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            if (configurable == null) {
                if (!showBaseUrlOverride) {
                    Text(
                        text = "This source has no configurable settings.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        val containerId = android.view.View.generateViewId()
                        val frame = android.widget.FrameLayout(ctx).apply { id = containerId }
                        val activity = ctx as? FragmentActivity
                        if (activity != null) {
                            val tag = "source_prefs_$sourceId"
                            if (activity.supportFragmentManager.findFragmentByTag(tag) == null) {
                                activity.supportFragmentManager
                                    .beginTransaction()
                                    .replace(containerId, SourcePreferenceFragments.create(sourceId), tag)
                                    .commitNowAllowingStateLoss()
                            }
                        }
                        frame
                    },
                )
            }
        }
    }
}
