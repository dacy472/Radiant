package com.radiant.webtoon.ui.extensions

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import com.radiant.webtoon.core.source.ExtensionLibCompatibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.model.Extension
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment

class ExtensionsViewModel(private val extensionManager: ExtensionManager) : ViewModel() {
    private val _installed = MutableStateFlow<List<Extension>>(emptyList())
    val installed: StateFlow<List<Extension>> = _installed

    private val _available = MutableStateFlow<List<Extension>>(emptyList())
    val available: StateFlow<List<Extension>> = _available

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _installIntent = MutableStateFlow<Intent?>(null)
    val installIntent: StateFlow<Intent?> = _installIntent

    private val _isUpdatingAll = MutableStateFlow(false)
    val isUpdatingAll: StateFlow<Boolean> = _isUpdatingAll

    init {
        viewModelScope.launch {
            combine(
                extensionManager.availableExtensions,
                extensionManager.installedExtensionInfos,
                extensionManager.availableUpdates,
            ) { _, _, _ -> }
                .collect { refreshExtensionLists() }
        }
        viewModelScope.launch {
            extensionManager.isLoading.collect {
                _isRefreshing.value = it
            }
        }
    }

    private fun refreshExtensionLists() {
        _installed.value = extensionManager.getInstalledAsExtensions()
        _available.value = extensionManager.getAvailableAsExtensions()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                extensionManager.refreshAvailable()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun install(extensionId: String) {
        viewModelScope.launch {
            try {
                extensionManager.installExtension(extensionId)
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Install failed: ${e.message}"
            }
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            _isUpdatingAll.value = true
            try {
                val result = extensionManager.updateAllAvailable()
                when {
                    result.failed > 0 && result.succeeded == 0 ->
                        _error.value = "Update failed: ${result.failures.firstOrNull()?.second ?: "unknown error"}"
                    result.failed > 0 ->
                        _error.value = "Updated ${result.succeeded}; ${result.failed} failed"
                    else -> _error.value = null
                }
            } catch (e: Exception) {
                _error.value = "Update failed: ${e.message}"
            } finally {
                _isUpdatingAll.value = false
            }
        }
    }

    fun uninstall(packageName: String) {
        viewModelScope.launch {
            try {
                extensionManager.uninstallExtension(packageName)
            } catch (e: Exception) {
                _error.value = "Uninstall failed: ${e.message}"
            }
        }
    }

    fun clearInstallIntent() {
        _installIntent.value = null
    }

    fun trust(packageName: String) {
        viewModelScope.launch {
            try {
                extensionManager.trustExtension(packageName)
            } catch (e: Exception) {
                _error.value = "Failed to trust extension: ${e.message}"
            }
        }
    }
}

@Composable
fun ExtensionsScreen(
    extensionManager: ExtensionManager,
    onSourceSettingsClick: (String) -> Unit = {},
) {
    val viewModel: ExtensionsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ExtensionsViewModel(extensionManager) as T
        }
    })

    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isUpdatingAll by viewModel.isUpdatingAll.collectAsStateWithLifecycle()
    val availableUpdates by extensionManager.availableUpdates.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val installIntent by viewModel.installIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(installIntent) {
        installIntent?.let { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
            viewModel.clearInstallIntent()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        error?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    err,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column {
                if (isRefreshing || isUpdatingAll) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (available.isEmpty() && installed.isEmpty() && !isRefreshing) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No verified channels yet.\nPull to refresh to load official sources.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item {
                            VerifiedSourcesHeader()
                        }

                        if (availableUpdates.isNotEmpty()) {
                            item {
                                UpdateAllBanner(
                                    updateCount = availableUpdates.size,
                                    isUpdating = isUpdatingAll,
                                    onUpdateAll = { viewModel.updateAll() },
                                )
                            }
                        }

                        if (installed.isNotEmpty()) {
                            item {
                                SectionLabel(
                                    title = "Active Channels",
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                )
                            }
                            items(installed, key = { "installed-${it.id}" }) { extension ->
                                InstalledChannelRow(
                                    extension = extension,
                                    extensionManager = extensionManager,
                                    onTrust = { viewModel.trust(extension.id) },
                                    onUninstall = { viewModel.uninstall(extension.id) },
                                    onUpdate = { viewModel.install(extension.id) },
                                    onSourceSettingsClick = onSourceSettingsClick,
                                )
                                HorizontalDivider()
                            }
                        }

                        if (available.isNotEmpty()) {
                            item {
                                SectionLabel(
                                    title = "Official Channels",
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                )
                            }
                            items(available, key = { "available-${it.id}" }) { extension ->
                                AvailableChannelRow(
                                    extension = extension,
                                    onInstall = { viewModel.install(extension.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            if ((isRefreshing || isUpdatingAll) && (installed.isNotEmpty() || available.isNotEmpty())) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

@Composable
private fun UpdateAllBanner(
    updateCount: Int,
    isUpdating: Boolean,
    onUpdateAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$updateCount update${if (updateCount == 1) "" else "s"} available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onUpdateAll,
            enabled = !isUpdating,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
        ) {
            Text(if (isUpdating) "Updating…" else "Update All")
        }
    }
}

@Composable
private fun VerifiedSourcesHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Verified Sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Official channels tested for stability and quality. Only Radiant-approved sources appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InstalledChannelRow(
    extension: Extension,
    extensionManager: ExtensionManager,
    onTrust: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onSourceSettingsClick: (String) -> Unit,
) {
    val installedExt = extension as? Extension.Installed
    val trustLabel = if (installedExt?.isTrusted == true) "Verified" else "Needs trust"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIcon(extension = extension)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                extension.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(extension.version ?: "")
                    append(" · ")
                    append(trustLabel)
                    if (installedExt?.libCompatibility == ExtensionLibCompatibility.TooNew) {
                        append(" · Update app required")
                    } else if (installedExt?.libCompatibility == ExtensionLibCompatibility.TooOld) {
                        append(" · Channel outdated")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (installedExt?.hasUpdate == true) {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            ) {
                Text("UPDATE")
            }
        } else if (installedExt?.isTrusted == true) {
            val sourceId = extensionManager.findSourceIdForPackage(extension.id)
            if (sourceId != null && extensionManager.getConfigurableSource(sourceId) != null) {
                IconButton(onClick = { onSourceSettingsClick(sourceId) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Source settings")
                }
            }
        } else {
            TextButton(onClick = onTrust) {
                Text("TRUST")
            }
        }
        IconButton(onClick = onUninstall) {
            Icon(Icons.Default.Delete, contentDescription = "Uninstall")
        }
    }
}

@Composable
private fun AvailableChannelRow(
    extension: Extension,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIcon(extension = extension)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                extension.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("Official channel")
                    extension.version?.takeIf { it.isNotBlank() }?.let { append(" · v$it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onInstall) {
            Text("INSTALL")
        }
    }
}
