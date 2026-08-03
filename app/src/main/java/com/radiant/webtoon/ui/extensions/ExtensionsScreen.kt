package com.radiant.webtoon.ui.extensions

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.database.ExtensionRepoEntity
import com.radiant.webtoon.core.model.Extension
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment

class ExtensionsViewModel(private val extensionManager: ExtensionManager) : ViewModel() {
    private val _installed = MutableStateFlow<List<Extension>>(emptyList())
    val installed: StateFlow<List<Extension>> = _installed

    private val _available = MutableStateFlow<List<Extension>>(emptyList())
    val available: StateFlow<List<Extension>> = _available

    private val _repos = MutableStateFlow<List<ExtensionRepoEntity>>(emptyList())
    val repos: StateFlow<List<ExtensionRepoEntity>> = _repos

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Hold an install intent to be launched by the composable
    private val _installIntent = MutableStateFlow<Intent?>(null)
    val installIntent: StateFlow<Intent?> = _installIntent

    init {
        // Reactively observe ExtensionManager state
        viewModelScope.launch {
            extensionManager.availableExtensions.collect {
                _available.value = extensionManager.getAvailableAsExtensions()
            }
        }
        viewModelScope.launch {
            extensionManager.installedExtensionInfos.collect {
                _installed.value = extensionManager.getInstalledAsExtensions()
            }
        }
        viewModelScope.launch {
            extensionManager.isLoading.collect {
                _isRefreshing.value = it
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                extensionManager.refreshAvailable()
                _repos.value = extensionManager.getRepos()
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
            val withUpdates = _installed.value
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
            withUpdates.forEach { ext ->
                try {
                    extensionManager.installExtension(ext.id)
                } catch (e: Exception) {
                    _error.value = "Update failed for ${ext.name}: ${e.message}"
                }
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

    fun addRepo(url: String) {
        viewModelScope.launch {
            try {
                extensionManager.addRepo(url)
                refresh()
            } catch (e: Exception) {
                _error.value = "Failed to add repo: ${e.message}"
            }
        }
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

    fun revokeTrust(packageName: String) {
        viewModelScope.launch {
            try {
                extensionManager.revokeTrust(packageName)
            } catch (e: Exception) {
                _error.value = "Failed to revoke trust: ${e.message}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    extensionManager: ExtensionManager,
    searchQuery: String = "",
    onSearchClick: () -> Unit,
    onBrowseClick: (String) -> Unit
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
    val error by viewModel.error.collectAsStateWithLifecycle()
    val installIntent by viewModel.installIntent.collectAsStateWithLifecycle()
    var showRepoDialog by remember { mutableStateOf(false) }
    var repoUrlInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Launch install/uninstall intent when available
    LaunchedEffect(installIntent) {
        installIntent?.let { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // Handle case where no activity can handle the intent
            }
            viewModel.clearInstallIntent()
        }
    }

    // One-shot catalog refresh when the Extensions tab opens
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Show error if any
            error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column {
                    if (isRefreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (available.isEmpty() && installed.isEmpty() && !isRefreshing) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No curated extensions yet.\nPull to refresh or check your repository.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        val filteredInstalled = installed.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        val filteredAvailable = available.filter { it.name.contains(searchQuery, ignoreCase = true) }

                        LazyColumn {
                            item {
                                Text(
                                    "Curated English sources",
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (filteredInstalled.isNotEmpty()) {
                                item {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Installed",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        val updateCount = filteredInstalled.count {
                                            (it as? Extension.Installed)?.hasUpdate == true
                                        }
                                        if (updateCount > 0) {
                                            TextButton(onClick = { viewModel.updateAll() }) {
                                                Text("Update all ($updateCount)")
                                            }
                                        }
                                    }
                                }
                                items(filteredInstalled, key = { it.id }) { extension ->
                                    val installedExt = extension as? Extension.Installed
                                    val trustLabel = if (installedExt?.isTrusted == true) "Trusted" else "Not trusted"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ExtensionIcon(extension = extension)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                extension.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${extension.version ?: ""} Â· $trustLabel",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        if (installedExt?.hasUpdate == true) {
                                            Button(
                                                onClick = { viewModel.install(extension.id) },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.tertiary
                                                )
                                            ) {
                                                Text("UPDATE")
                                            }
                                        } else if (installedExt?.isTrusted != true) {
                                            TextButton(onClick = { viewModel.trust(extension.id) }) {
                                                Text("TRUST")
                                            }
                                        }
                                        IconButton(onClick = { viewModel.uninstall(extension.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Uninstall")
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }

                            if (filteredAvailable.isNotEmpty()) {
                                item {
                                    Text(
                                        "Available",
                                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(filteredAvailable, key = { it.id }) { extension ->
                                    val availableExt = extension as? Extension.Available
                                    val lang = availableExt?.lang?.uppercase().orEmpty()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ExtensionIcon(extension = extension)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                extension.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                buildString {
                                                    if (lang.isNotBlank()) append(lang)
                                                    if (availableExt?.nsfw == true) {
                                                        if (isNotEmpty()) append(" · ")
                                                        append("18+")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { viewModel.install(extension.id) }) {
                                            Text("INSTALL")
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                // Optional subtle overlay if refreshing
                if (isRefreshing && (installed.isNotEmpty() || available.isNotEmpty())) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }

    // Add repo dialog
    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = { Text("Add Extension Repository") },
            text = {
                Column {
                    Text(
                        "Enter the URL to a Mihon/Tachiyomi repository (e.g., a GitHub raw URL to index.min.json).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = repoUrlInput,
                        onValueChange = { repoUrlInput = it },
                        placeholder = { Text("https://raw.githubusercontent.com/...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (repoUrlInput.isNotBlank()) {
                        viewModel.addRepo(repoUrlInput.trim())
                        repoUrlInput = ""
                        showRepoDialog = false
                    }
                }) {
                    Text("ADD")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepoDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}
