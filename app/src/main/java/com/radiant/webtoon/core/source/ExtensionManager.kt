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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.network.NetworkHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    private val _hideNsfw = MutableStateFlow(true)

    // Available extensions from all repos (not yet installed)
    private val _availableExtensions = MutableStateFlow<List<RepoExtensionEntry>>(emptyList())
    val availableExtensions: StateFlow<List<RepoExtensionEntry>> = _availableExtensions

    // Installed extension infos (from PackageManager scan)
    private val _installedExtensionInfos = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installedExtensionInfos: StateFlow<List<ExtensionInfo>> = _installedExtensionInfos

    // Dedicated source registry for reliable instance retrieval
    private val sourceRegistry = ConcurrentHashMap<String, Source>()
    private val loadMutex = Mutex()
    private val isRefreshingRepos = AtomicBoolean(false)

    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val networkHelper: NetworkHelper by injectLazy()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRepoRefreshing = MutableStateFlow(false)

    private val _trustedPackages = MutableStateFlow<Set<String>>(emptySet())

    private companion object {
        const val DEFAULT_REPO_URL =
            "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"

        /** Multi-lang ("all") packs are allowed; they include English sources. */
        private val ENABLED_EXTENSION_LANGS = setOf("en", "all")
        private val ENABLED_SOURCE_LANGS = setOf("en")
    }

    fun isExtensionLanguageEnabled(lang: String?): Boolean {
        val normalized = lang?.lowercase()?.trim().orEmpty()
        return normalized.isEmpty() || normalized in ENABLED_EXTENSION_LANGS
    }

    fun isSourceLanguageEnabled(lang: String): Boolean {
        val normalized = lang.lowercase().trim()
        return normalized.isEmpty() || normalized in ENABLED_SOURCE_LANGS
    }

    // init MUST run after all StateFlows above — launching earlier races and NPEs on _isLoading
    init {
        scope.launch(Dispatchers.IO) {
            loadInstalledExtensions()
        }

        scope.launch(Dispatchers.IO) {
            settingsManager.nsfwFilter.collect { filterEnabled ->
                _hideNsfw.value = filterEnabled
                val currents = _installedExtensions.value
                if (currents.isNotEmpty()) {
                    _installedExtensions.value = currents.toList()
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            settingsManager.pinnedSources.collect { pinnedIds ->
                sourceRegistry.values.forEach { source ->
                    source.isPinned = pinnedIds.contains(source.id)
                }

                val currents = _installedExtensions.value
                if (currents.isNotEmpty()) {
                    _installedExtensions.value = currents.toList()
                }
            }
        }
    }

    suspend fun togglePin(sourceId: String) {
        settingsManager.togglePinnedSource(sourceId)
    }

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

        // Prevent overlapping refreshes (was causing infinite load/scroll loops)
        if (!isRefreshingRepos.compareAndSet(false, true)) {
            Log.d(tag, "Repo refresh already in progress, skipping")
            return
        }

        // Step B & C: Launch background coroutine to fetch live URL, cache it, and emit it
        scope.launch(Dispatchers.IO) {
            _isRepoRefreshing.value = true
            _isLoading.value = true
            try {
                ensureDefaultRepo()
                val repos = repoDao.getAll()
                val repoUrls = if (repos.isEmpty()) {
                    listOf(DEFAULT_REPO_URL)
                } else {
                    repos.map { it.baseUrl }
                }

                val allEntries = mutableListOf<RepoExtensionEntry>()

                for (url in repoUrls) {
                    try {
                        val index = extensionLoader.fetchRepoIndex(url)
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
                    // Same extension can appear in multiple repos — keep first, unique by id
                    _availableExtensions.value = allEntries.distinctBy { it.id }
                    try {
                        val newJson = gson.toJson(_availableExtensions.value)
                        prefs.edit().putString("cached_extensions", newJson).apply()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to cache extensions", e)
                    }
                    installDefaultExtensionsIfFirstLaunch(allEntries)
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

                // Reload sources only when fingerprint changed (avoid DEX reload on every UI refresh)
                loadInstalledExtensions(force = false)
            } catch (e: Exception) {
                Log.e(tag, "Error refreshing available extensions", e)
            } finally {
                _isRepoRefreshing.value = false
                _isLoading.value = false
                isRefreshingRepos.set(false)
            }
        }
    }

    private suspend fun installDefaultExtensionsIfFirstLaunch(entries: List<RepoExtensionEntry>) {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_launch_defaults_installed", false)) return

        val curated = entries.filter { ExtensionAllowlist.isAllowed(it.id) }
        val bySlug = curated.associateBy { ExtensionAllowlist.slugOf(it.id) }
        val defaultEntries = ExtensionAllowlist.DEFAULT_INSTALL_SLUGS.mapNotNull { bySlug[it] }
            .ifEmpty { curated.take(6) }

        Log.i(tag, "First launch: Auto-installing ${defaultEntries.size} curated extensions...")
        var installedAny = false

        for (entry in defaultEntries) {
            val id = entry.id
            if (entry.repoUrl == null) continue
            try {
                val downloadedFile = extensionLoader.downloadExtensionApk(entry.repoUrl!!, entry)
                val destFile = java.io.File(extensionLoader.extensionsDir, "${entry.id}.apk")
                if (destFile.exists()) destFile.setWritable(true)
                downloadedFile.copyTo(destFile, overwrite = true)
                downloadedFile.delete()
                extensionLoader.invalidateLoadedExtension(entry.id)

                val existing = installedDao.getById(id)
                if (existing != null) {
                    installedDao.setTrusted(id, true)
                } else {
                    installedDao.insert(InstalledExtensionEntity(id = id, name = entry.name, isTrusted = true))
                }

                installedAny = true
                Log.i(tag, "Successfully auto-installed curated extension: $id")
            } catch (e: Exception) {
                Log.e(tag, "Failed to auto-install curated extension $id", e)
            }
        }

        if (installedAny) {
            prefs.edit().putBoolean("first_launch_defaults_installed", true).apply()
            refreshTrustCache()
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
     * Downloads the APK into the internal extensions directory and trusts it for loading.
     */
    suspend fun installExtension(extensionId: String) = withContext(Dispatchers.IO) {
        val available = _availableExtensions.value.find { it.id == extensionId }
        val entry = available ?: run {
            val repos = repoDao.getAll()
            val repoUrls = if (repos.isEmpty()) {
                listOf(DEFAULT_REPO_URL)
            } else {
                repos.map { it.baseUrl }
            }
            var found: RepoExtensionEntry? = null
            for (url in repoUrls) {
                try {
                    val index = extensionLoader.fetchRepoIndex(url)
                    found = index.extensions.find { it.id == extensionId }?.copy(repoUrl = url)
                    if (found != null) break
                } catch (e: Exception) {
                    Log.e(tag, "Failed to look up $extensionId in $url", e)
                }
            }
            found
        } ?: throw Exception("Extension '$extensionId' not found")

        if (!ExtensionAllowlist.isAllowed(extensionId)) {
            throw Exception("This extension is not in Radiant's curated source list")
        }

        if (!isExtensionLanguageEnabled(entry.lang)) {
            throw Exception("Only English extensions can be installed")
        }

        val repoUrl = entry.repoUrl
            ?: throw Exception("Extension '$extensionId' has no repository URL")

        Log.i(tag, "Installing extension locally: $extensionId")
        val downloadedFile = extensionLoader.downloadExtensionApk(repoUrl, entry)
        val destFile = java.io.File(extensionLoader.extensionsDir, "${entry.id}.apk")
        if (destFile.exists()) destFile.setWritable(true)
        downloadedFile.copyTo(destFile, overwrite = true)
        downloadedFile.delete()
        extensionLoader.invalidateLoadedExtension(entry.id)

        val existing = installedDao.getById(extensionId)
        if (existing != null) {
            installedDao.setTrusted(extensionId, true)
        } else {
            installedDao.insert(
                InstalledExtensionEntity(id = extensionId, name = entry.name, isTrusted = true)
            )
        }

        refreshTrustCache()
        loadInstalledExtensions(force = true)
        Log.i(tag, "Successfully installed and trusted extension: $extensionId")
    }

    /**
     * Uninstall without refreshing the catalog (safe during loadInstalledExtensions).
     */
    private suspend fun removeExtensionLocally(packageName: String) {
        extensionLoader.deleteLocalExtension(packageName)
        installedDao.delete(packageName)
        extensionLoader.invalidateLoadedExtension(packageName)

        val registryEntriesToRemove = sourceRegistry.filter { (key, source) ->
            key == packageName || (source is TachiyomiSourceAdapter && source.icon.contains(packageName))
        }.keys
        registryEntriesToRemove.forEach { sourceRegistry.remove(it) }

        _installedExtensions.update { current ->
            current.filterNot { it is TachiyomiSourceAdapter && it.icon.contains(packageName) }
        }
    }

    /**
     * Uninstall an extension.
     * Deletes the local file and cached data, and updates UI state.
     */
    suspend fun uninstallExtension(packageName: String) = withContext(Dispatchers.IO) {
        Log.i(tag, "Uninstalling extension: $packageName")
        removeExtensionLocally(packageName)

        val currentInstalled = _installedExtensionInfos.value.toMutableList()
        currentInstalled.removeAll { it.packageName == packageName }
        _installedExtensionInfos.value = currentInstalled

        Log.i(tag, "Extension $packageName fully uninstalled and unloaded.")
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

    /**
     * Ensures a default repo row exists. Must NOT call [refreshAvailable] —
     * that used to recurse via loadInstalledExtensions → setupDefaultRepo → refreshAvailable
     * and caused an infinite loading/scroll loop on the Extensions tab.
     */
    private suspend fun ensureDefaultRepo() {
        try {
            if (repoDao.getAll().isEmpty()) {
                repoDao.insert(
                    ExtensionRepoEntity(
                        baseUrl = DEFAULT_REPO_URL,
                        name = "Keiyoushi",
                        shortName = "Official"
                    )
                )
                Log.d(tag, "Inserted default extension repo")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to ensure default repo", e)
        }
    }

    // ─── Load installed extensions (called on app startup) ───

    suspend fun loadInstalledExtensions(force: Boolean = false) = withContext(Dispatchers.IO) {
        // Avoid clobbering an in-flight repo refresh unless explicitly forced
        if (_isLoading.value && !force && !_isRepoRefreshing.value) return@withContext

        loadMutex.withLock {
            val ownsLoadingFlag = !_isRepoRefreshing.value
            if (ownsLoadingFlag) _isLoading.value = true
            try {
                ensureDefaultRepo()
                refreshTrustCache()

                Log.d(tag, "Scanning for extensions (system + local)...")
                val systemInfos = extensionLoader.scanInstalledExtensions()
                val localInfos = extensionLoader.scanLocalExtensions()

                // Merge: Local extensions take precedence
                val localPkgs = localInfos.map { it.packageName }.toSet()
                val installedInfos = localInfos + systemInfos.filter { !localPkgs.contains(it.packageName) }

                val trusted = _trustedPackages.value

                // Drop non-curated local APKs so DexClassLoaders / memory stay small
                val disallowed = installedInfos.filter { !ExtensionAllowlist.isAllowed(it.packageName) }
                for (info in disallowed) {
                    Log.i(tag, "Removing non-curated extension: ${info.packageName}")
                    removeExtensionLocally(info.packageName)
                }
                val curatedInfos = installedInfos.filter { ExtensionAllowlist.isAllowed(it.packageName) }
                _installedExtensionInfos.value = curatedInfos

                // Optimization: Generate a fingerprint of current state to avoid redundant class loading
                val currentFingerprint = curatedInfos
                    .filter { trusted.contains(it.packageName) }
                    .joinToString("|") { "${it.packageName}:${it.versionCode}" } + "|allowlist|lang=en"

                // Skip even when the previous load produced 0 sources (e.g. all failed).
                // Requiring isNotEmpty() caused a tight retry loop on Android 14+ writable-dex failures.
                if (!force && currentFingerprint == lastLoadedFingerprint) {
                    Log.d(tag, "No changes in trusted extensions. Skipping source reload.")
                    return@withLock
                }

                val loadedSources = mutableListOf<Source>()
                sourceRegistry.clear() // Clear registry before repopulating

                Log.d(tag, "Installed curated extensions: ${curatedInfos.size}. Trusted packages: ${trusted.size}")

                // Load trusted allowlisted extensions with bounded concurrency (max 2)
                val perExtensionResults = curatedInfos
                    .filter { extInfo ->
                        val isTrusted = trusted.contains(extInfo.packageName)
                        if (!isTrusted) {
                            Log.w(tag, "Extension NOT trusted: ${extInfo.packageName}. Skipping source load.")
                        }
                        isTrusted
                    }
                    .map { extInfo ->
                        async(Dispatchers.IO) {
                            com.radiant.webtoon.core.concurrent.AppWorkQueues.withExtensionLoad {
                                try {
                                    Log.d(tag, "Loading sources for extension: ${extInfo.packageName}")
                                    val tachiyomiSources = extensionLoader.loadExtensionSources(extInfo)
                                    val iconUri = if (extInfo.apkPath.startsWith(context.filesDir.absolutePath)) {
                                        "apk:${extInfo.apkPath}"
                                    } else {
                                        "pkg:${extInfo.packageName}"
                                    }
                                    tachiyomiSources.map { tSource ->
                                        TachiyomiSourceAdapter(tSource, iconUri) to extInfo.packageName
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to load extension: ${extInfo.name} (${extInfo.packageName})", e)
                                    emptyList()
                                }
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()

                // Register English sources only — other languages stay loaded in the APK but disabled
                for ((adapter, pkgName) in perExtensionResults) {
                    if (!isSourceLanguageEnabled(adapter.lang)) continue
                    loadedSources.add(adapter)
                    sourceRegistry[adapter.id] = adapter
                    sourceRegistry.putIfAbsent(pkgName, adapter)
                }

                Log.i(
                    tag,
                    "Finished loading extensions. Instantiated ${loadedSources.size} English sources " +
                        "(from ${perExtensionResults.size} total)."
                )

                _installedExtensions.value = loadedSources
                lastLoadedFingerprint = currentFingerprint
                Log.i(tag, "Total sources active: ${loadedSources.size} (Registry size: ${sourceRegistry.size})")
            } catch (e: Exception) {
                Log.e(tag, "Error loading installed extensions", e)
                if (_installedExtensions.value.isEmpty()) {
                    _installedExtensions.value = emptyList()
                }
            } finally {
                if (ownsLoadingFlag) _isLoading.value = false
            }
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

        return _installedExtensionInfos.value
            .filter { ExtensionAllowlist.isAllowed(it.packageName) }
            .map { info ->
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
     * Only curated English sources are listed.
     */
    fun getAvailableAsExtensions(): List<Extension> {
        val installedPkgs = _installedExtensionInfos.value.map { it.packageName }.toSet()
        val trusted = _trustedPackages.value
        return _availableExtensions.value
            .distinctBy { it.id }
            .filter { entry -> ExtensionAllowlist.isAllowed(entry.id) }
            .filter { entry -> !installedPkgs.contains(entry.id) }
            // NSFW filter does not hide allowlisted packs (Toonily, Manga18fx, etc.)
            .sortedBy { it.name.lowercase() }
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
                isInstalled = installedPkgs.contains(entry.id),
                isLanguageEnabled = true
            )
        }
    }
}
