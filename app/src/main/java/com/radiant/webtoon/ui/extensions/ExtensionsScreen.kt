package com.radiant.webtoon.ui.extensions

import android.content.Intent
import android.net.Uri
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

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
                refresh() // Refresh state after install
            } catch (e: Exception) {
                _error.value = "Install failed: ${e.message}"
            }
        }
    }

    fun uninstall(packageName: String) {
        viewModelScope.launch {
            try {
                extensionManager.uninstallExtension(packageName)
                refresh() // Refresh state after uninstall
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
                refresh()
            } catch (e: Exception) {
                _error.value = "Failed to trust extension: ${e.message}"
            }
        }
    }

    fun revokeTrust(packageName: String) {
        viewModelScope.launch {
            try {
                extensionManager.revokeTrust(packageName)
                refresh()
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
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val installIntent by viewModel.installIntent.collectAsStateWithLifecycle()
    var showRepoDialog by remember { mutableStateOf(false) }
    var repoUrlInput by remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Launch install/uninstall intent when available
    LaunchedEffect(installIntent) {
        installIntent?.let { intent ->
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Handle case where no activity can handle the intent
            }
            viewModel.clearInstallIntent()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
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

            Box(Modifier.fillMaxSize()) {
                Column {
                    if (isRefreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    
                            // Available tab
                            if (available.isEmpty() && !isRefreshing) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    Text(
                                        "No extensions available.\nAdd a repository URL.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else {
                                val filteredInstalled = installed.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                val filteredAvailable = available.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                
                                LazyColumn {
                                    if (filteredInstalled.isNotEmpty()) {
                                        item {
                                            Text(
                                                "Installed",
                                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        items(filteredInstalled, key = { it.id }) { extension ->
                                            val installedExt = extension as? Extension.Installed
                                            ListItem(
                                                headlineContent = { Text(extension.name) },
                                                supportingContent = { Text(extension.version ?: "") },
                                                leadingContent = {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(extension.icon)
                                                            .crossfade(true)
                                                            .build(),
                                                        placeholder = rememberVectorPainter(Icons.Default.Extension),
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(40.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                    )
                                                },
                                                trailingContent = {
                                                    if (installedExt?.hasUpdate == true) {
                                                        Button(
                                                            onClick = { 
                                                                val downloadUrl = installedExt.downloadUrl
                                                                if (downloadUrl != null) {
                                                                    val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                                                        setTitle("Updating ${extension.name}")
                                                                        setMimeType("application/vnd.android.package-archive")
                                                                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${extension.id}_update.apk")
                                                                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                                    }
                                                                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                                                    downloadManager.enqueue(request)
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                                        ) {
                                                            Text("UPDATE")
                                                        }
                                                    } else {
                                                        IconButton(onClick = { /* Settings */ }) {
                                                            Icon(Icons.Default.Settings, contentDescription = null)
                                                        }
                                                    }
                                                }
                                            )
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
                                            ListItem(
                                                headlineContent = { Text(extension.name) },
                                                supportingContent = { Text((extension as? Extension.Available)?.lang?.uppercase() ?: "") },
                                                leadingContent = {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(LocalContext.current)
                                                            .data(extension.icon)
                                                            .crossfade(true)
                                                            .build(),
                                                        placeholder = rememberVectorPainter(Icons.Default.Extension),
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(40.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                    )
                                                },
                                                trailingContent = {
                                                    Button(onClick = { 
                                                        val downloadUrl = (extension as? Extension.Available)?.downloadUrl
                                                        if (downloadUrl != null) {
                                                            val pkgName = (extension as? Extension.Available)?.id ?: extension.name
                                                            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                                                setTitle("Downloading ${extension.name}")
                                                                setMimeType("application/vnd.android.package-archive")
                                                                addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${pkgName}_${System.currentTimeMillis()}.apk")
                                                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                            }
                                                            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                                            downloadManager.enqueue(request)
                                                        }
                                                    }) {
                                                        Text("INSTALL")
                                                    }
                                                }
                                            )
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                }

                // Optional subtle overlay if refreshing
                if (isRefreshing && (installed.isNotEmpty() || available.isNotEmpty())) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
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
                    Spacer(Modifier.height(8.dp))
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
