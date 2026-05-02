package com.radiant.webtoon.core.source

import com.radiant.webtoon.core.source.Source
import eu.kanade.tachiyomi.source.Source as TachiyomiSource
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.radiant.webtoon.core.database.ExtensionRepoEntity
import com.radiant.webtoon.core.database.ExtensionRepoDao
import com.radiant.webtoon.core.database.InstalledExtensionDao
import com.radiant.webtoon.core.database.InstalledExtensionEntity
import com.radiant.webtoon.core.model.Extension
import com.radiant.webtoon.core.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.network.NetworkHelper

/**
 * Manager responsible for the full extension lifecycle:
 * - Managing trusted repository URLs
 * - Fetching available extensions from repos
 * - Installing/uninstalling extension APKs
 * - Scanning installed extension APKs via PackageManager
 * - Loading extension sources into memory
 * - Providing access to active sources
 */
class ExtensionManager(
    private val context: Context,
    private val extensionLoader: ExtensionLoader,
    private val repoDao: ExtensionRepoDao,
    private val installedDao: InstalledExtensionDao,
    private val settingsManager: SettingsManager,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val tag = "ExtensionManager"

    // Active extensions loaded in memory (cached for instant access)
    private val _installedExtensions = MutableStateFlow<List<Source>>(emptyList())
    val installedExtensions: StateFlow<List<Source>> = _installedExtensions

    init {
        // Trigger initial background load immediately upon startup
        scope.launch(Dispatchers.IO) {
            loadInstalledExtensions()
        }
        
        // Dynamically track pins
        scope.launch(Dispatchers.IO) {
            settingsManager.pinnedSources.collect { pinnedIds ->
                sourceRegistry.values.forEach { source ->
                    source.isPinned = pinnedIds.contains(source.id)
                }
                
                // Force StateFlow update so UI recomposes
                val currents = _installedExtensions.value
                if (currents.isNotEmpty()) {
                    _installedExtensions.value = currents.toList() // trigger recomposition
                }
            }
        }
    }

    suspend fun togglePin(sourceId: String) {
        settingsManager.togglePinnedSource(sourceId)
    }

    // Available extensions from all repos (not yet installed)
    private val _availableExtensions = MutableStateFlow<List<RepoExtensionEntry>>(emptyList())
    val availableExtensions: StateFlow<List<RepoExtensionEntry>> = _availableExtensions

    // Installed extension infos (from PackageManager scan)
    private val _installedExtensionInfos = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installedExtensionInfos: StateFlow<List<ExtensionInfo>> = _installedExtensionInfos

    // Dedicated source registry for reliable instance retrieval
    private val sourceRegistry = mutableMapOf<String, Source>()

    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val networkHelper: NetworkHelper by injectLazy()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _trustedPackages = MutableStateFlow<Set<String>>(emptySet())

    // ─── Repository management ───

    suspend fun addRepo(url: String) = withContext(Dispatchers.IO) {
        try {
            val index = extensionLoader.fetchRepoIndex(url)
            repoDao.insert(ExtensionRepoEntity(
                baseUrl = url,
                name = index.name,
                shortName = index.description
            ))
            Log.d(tag, "Added repo: ${index.name} ($url)")
            refreshAvailable()
        } catch (e: Exception) {
            Log.e(tag, "Failed to add repo: $url", e)
            throw e
        }
    }

    suspend fun removeRepo(baseUrl: String) = withContext(Dispatchers.IO) {
        repoDao.delete(baseUrl)
        refreshAvailable()
    }

    suspend fun getRepos(): List<ExtensionRepoEntity> = withContext(Dispatchers.IO) {
        repoDao.getAll()
    }

    // ─── Available extensions ───

    suspend fun refreshAvailable() {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        
        // Step A: Immediately read the cached JSON string, parse it, and emit it to the UI
        val cachedJson = prefs.getString("cached_extensions", null)
        if (cachedJson != null) {
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
                val cachedExtensions: List<RepoExtensionEntry> = gson.fromJson(cachedJson, listType)
                _availableExtensions.value = cachedExtensions
                Log.d(tag, "Loaded ${cachedExtensions.size} extensions from cache")
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse cached extensions", e)
            }
        }

        // Step B & C: Launch background coroutine to fetch live URL, cache it, and emit it
        scope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val repos = repoDao.getAll()
                // Default repo logic: if the user's configured repository list is empty
                val repoUrls = if (repos.isEmpty()) {
                    listOf("https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json")
                } else {
                    repos.map { it.baseUrl }
                }

                val allEntries = mutableListOf<RepoExtensionEntry>()

                for (url in repoUrls) {
                    try {
                        val index = extensionLoader.fetchRepoIndex(url)
                        val repoBaseUrl = url.substringBeforeLast("/")
                        val processedExtensions = index.extensions.map { entry ->
                            val iconUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/icon/${entry.id}.png"
                            entry.copy(icon = iconUrl, repoUrl = url)
                        }
                        allEntries.addAll(processedExtensions)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to fetch repo: $url", e)
                    }
                }

                if (allEntries.isNotEmpty()) {
                    _availableExtensions.value = allEntries
                    try {
                        val newJson = gson.toJson(allEntries)
                        prefs.edit().putString("cached_extensions", newJson).apply()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to cache extensions", e)
                    }
                } else if (repoUrls.isNotEmpty() && cachedJson == null) {
                    _availableExtensions.value = emptyList()
                }

                // Refresh both local and system extensions
                val systemInfos = extensionLoader.scanInstalledExtensions()
                val localInfos = extensionLoader.scanLocalExtensions()
                
                // Merge: Local extensions take precedence if package names overlap
                val localPkgs = localInfos.map { it.packageName }.toSet()
                val installedInfos = localInfos + systemInfos.filter { !localPkgs.contains(it.packageName) }
                
                _installedExtensionInfos.value = installedInfos

                // CRITICAL: Reload the actual Source classes into memory so they can be searched
                loadInstalledExtensions()
            } catch (e: Exception) {
                Log.e(tag, "Error refreshing available extensions", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Publicly exposes the ability to reload already-scanned sources.
     * Useful for tabs needing to ensure extensions are active.
     */
    suspend fun refreshSources() {
        _installedExtensions.value = emptyList() // Clear to force a fresh reload
        loadInstalledExtensions()
    }

    // ─── Extension install/uninstall ───

    /**
     * Install an extension by ID.
     * Downloads the APK and moves it to the internal extensions directory.
     */
    suspend fun installExtension(extensionId: String) = withContext(Dispatchers.IO) {
        val repos = repoDao.getAll()
        for (repo in repos) {
            try {
                val index = extensionLoader.fetchRepoIndex(repo.baseUrl)
                val entry = index.extensions.find { it.id == extensionId }
                if (entry != null) {
                    // Start download via Manager for system installation
                    extensionLoader.downloadExtensionViaManager(repo.baseUrl, entry)
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to enqueue download for $extensionId", e)
            }
        }
        throw Exception("Extension '$extensionId' not found or failed to enqueue")
    }

    /**
     * Uninstall an extension.
     * Deletes the local file and cached data, and updates UI state.
     */
    suspend fun uninstallExtension(packageName: String) = withContext(Dispatchers.IO) {
        Log.i(tag, "Uninstalling extension: $packageName")
        
        // a) Delete the .apk file from storage
        val deletedFile = extensionLoader.deleteLocalExtension(packageName)
        if (deletedFile) {
            Log.d(tag, "Deleted APK for $packageName")
        }
        
        // b) Delete the extension's record from the local Room Database
        installedDao.delete(packageName)
        
        // c) Unload the class from the memory registry and update flows
        
        // Remove from unified source registry (both numeric ID and package name entries)
        val registryEntriesToRemove = sourceRegistry.filter { (key, source) -> 
            key == packageName || (source is TachiyomiSourceAdapter && source.icon.contains(packageName))
        }.keys
        registryEntriesToRemove.forEach { sourceRegistry.remove(it) }

        // Update active extensions list
        _installedExtensions.update { current ->
            current.filterNot { it is TachiyomiSourceAdapter && it.icon.contains(packageName) }
        }
        
        // Update installed infos list so UI removes it instantly
        val currentInstalled = _installedExtensionInfos.value.toMutableList()
        currentInstalled.removeAll { it.packageName == packageName }
        _installedExtensionInfos.value = currentInstalled
        
        Log.i(tag, "Extension $packageName fully uninstalled and unloaded.")

        // d) Final refresh to ensure 'Available' list recognizes it's no longer installed
        refreshAvailable()
    }

    // Keep legacy intent support for system-installed apps if needed, but rename
    fun getSystemUninstallIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    private var lastLoadedFingerprint: String = ""

    // ─── Trust management ───

    suspend fun trustExtension(packageName: String) = withContext(Dispatchers.IO) {
        val existing = installedDao.getById(packageName)
        if (existing != null) {
            installedDao.setTrusted(packageName, true)
        } else {
            installedDao.insert(InstalledExtensionEntity(id = packageName, name = packageName, isTrusted = true))
        }
        refreshTrustCache()
        loadInstalledExtensions(force = true)
    }

    suspend fun revokeTrust(packageName: String) = withContext(Dispatchers.IO) {
        installedDao.setTrusted(packageName, false)
        refreshTrustCache()
        loadInstalledExtensions(force = true)
    }

    private suspend fun refreshQueueState() {
        // Placeholder for queue logic
    }

    private suspend fun refreshTrustCache() {
        _trustedPackages.value = installedDao.getAll().filter { it.isTrusted }.map { it.id }.toSet()
    }

    private suspend fun setupDefaultRepo() {
        try {
            if (repoDao.getAll().isEmpty()) {
                addRepo("https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup default repo", e)
        }
    }

    // ─── Load installed extensions (called on app startup) ───

    suspend fun loadInstalledExtensions(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (_isLoading.value && !force) return@withContext
        _isLoading.value = true
        try {
            setupDefaultRepo()
            refreshTrustCache()
            
            Log.d(tag, "Scanning for extensions (system + local)...")
            val systemInfos = extensionLoader.scanInstalledExtensions()
            val localInfos = extensionLoader.scanLocalExtensions()
            
            // Merge: Local extensions take precedence
            val localPkgs = localInfos.map { it.packageName }.toSet()
            val installedInfos = localInfos + systemInfos.filter { !localPkgs.contains(it.packageName) }
            
            _installedExtensionInfos.value = installedInfos

            val trusted = _trustedPackages.value
            
            // Optimization: Generate a fingerprint of current state to avoid redundant class loading
            val currentFingerprint = installedInfos
                .filter { trusted.contains(it.packageName) }
                .joinToString("|") { "${it.packageName}:${it.versionCode}" }
            
            if (!force && currentFingerprint == lastLoadedFingerprint && _installedExtensions.value.isNotEmpty()) {
                Log.d(tag, "No changes in trusted extensions. Skipping source reload.")
                return@withContext
            }

            val loadedSources = mutableListOf<Source>()
            sourceRegistry.clear() // Clear registry before repopulating

            Log.d(tag, "Installed extensions found: ${installedInfos.size}. Trusted packages: ${trusted.size}")

            // Load all trusted extensions concurrently to avoid sequential ClassLoader bottleneck
            val perExtensionResults = installedInfos
                .filter { extInfo ->
                    val isTrusted = trusted.contains(extInfo.packageName)
                    if (!isTrusted) {
                        Log.w(tag, "Extension NOT trusted: ${extInfo.packageName}. Skipping source load.")
                    }
                    isTrusted
                }
                .map { extInfo ->
                    async(Dispatchers.IO) {
                        try {
                            Log.d(tag, "Loading sources for extension: ${extInfo.packageName}")
                            val tachiyomiSources = extensionLoader.loadExtensionSources(extInfo)
                            val iconUri = "pkg:${extInfo.packageName}"
                            tachiyomiSources.map { tSource ->
                                TachiyomiSourceAdapter(tSource, iconUri) to extInfo.packageName
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to load extension: ${extInfo.name} (${extInfo.packageName})", e)
                            emptyList()
                        }
                    }
                }
                .awaitAll() // Wait for all extensions to finish loading simultaneously
                .flatten()  // Flatten List<List<Pair>> -> List<Pair>

            // Register results and populate loadedSources
            for ((adapter, pkgName) in perExtensionResults) {
                loadedSources.add(adapter)
                sourceRegistry[adapter.id] = adapter
                sourceRegistry[pkgName] = adapter
            }

            Log.i(tag, "Finished loading extensions. Instantiated ${loadedSources.size} sources.")

            // No language or NSFW filter — show all installed sources
            Log.i(tag, "All installed sources loaded: ${loadedSources.size}")

            _installedExtensions.value = loadedSources
            lastLoadedFingerprint = currentFingerprint
            Log.i(tag, "Total sources active: ${loadedSources.size} (Registry size: ${sourceRegistry.size})")
        } catch (e: Exception) {
            Log.e(tag, "Error loading installed extensions", e)
            if (_installedExtensions.value.isEmpty()) {
                _installedExtensions.value = emptyList()
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Finds the first source ID for a given package name.
     */
    fun findSourceIdForPackage(packageName: String): String? {
        return _installedExtensions.value.find { it is TachiyomiSourceAdapter && it.icon.contains(packageName) }?.id
    }

    // ─── Source access ───

    fun getSource(id: String): Source? {
        return sourceRegistry[id]
    }

    fun getAllSources(): List<Source> {
        return _installedExtensions.value
    }

    fun registerSource(source: Source) {
        val current = _installedExtensions.value.toMutableList()
        if (current.none { it.id == source.id }) {
            current.add(source)
            _installedExtensions.value = current
            sourceRegistry[source.id] = source
        }
    }

    /**
     * Convert installed extension infos to Extension model for UI display.
     */
    fun getInstalledAsExtensions(): List<Extension> {
        val trusted = _trustedPackages.value
        val availableMap = _availableExtensions.value.associateBy { it.id }

        return _installedExtensionInfos.value.map { info ->
            val iconUri = if (info.apkPath.startsWith(context.filesDir.absolutePath)) {
                "apk:${info.apkPath}"
            } else {
                "pkg:${info.packageName}"
            }
            
            val availableMatch = availableMap[info.packageName]
            val hasUpdate = if (availableMatch != null) {
                availableMatch.versionCode > info.versionCode
            } else false
            
            val downloadUrl = if (hasUpdate && availableMatch != null) {
                val base = availableMatch.repoUrl?.substringBeforeLast("/")
                if (base != null) "$base/apk/${availableMatch.fileName}" else null
            } else null

            Extension.Installed(
                id = info.packageName,
                name = info.name,
                icon = iconUri,
                version = info.versionName,
                versionName = info.versionName,
                versionCode = info.versionCode.toInt(),
                nsfw = info.nsfw,
                isTrusted = trusted.contains(info.packageName),
                pkgName = info.packageName,
                sources = sourceRegistry.values.filter { it is TachiyomiSourceAdapter && it.icon.contains(info.packageName) },
                hasUpdate = hasUpdate,
                downloadUrl = downloadUrl
            )
        }
    }

    /**
     * Convert available repo entries to Extension model for UI display.
     * Marks entries as installed if their package name matches a scanned extension.
     */
    fun getAvailableAsExtensions(): List<Extension> {
        val installedPkgs = _installedExtensionInfos.value.map { it.packageName }.toSet()
        val trusted = _trustedPackages.value
        return _availableExtensions.value
            .filter { entry -> !installedPkgs.contains(entry.id) }
            .map { entry ->
            val effectiveBaseUrl = entry.baseUrl ?: entry.sources?.firstOrNull()?.baseUrl ?: ""
            val downloadUrl = if (entry.repoUrl != null) {
                val base = entry.repoUrl!!.substringBeforeLast("/")
                "$base/apk/${entry.fileName}"
            } else null
            
            Extension.Available(
                id = entry.id,
                name = entry.name,
                icon = entry.icon,
                version = entry.version,
                nsfw = entry.nsfw == 1,
                isTrusted = trusted.contains(entry.id),
                lang = entry.lang,
                description = entry.description,
                baseUrl = effectiveBaseUrl,
                downloadUrl = downloadUrl,
                isInstalled = installedPkgs.contains(entry.id)
            )
        }
    }
}
