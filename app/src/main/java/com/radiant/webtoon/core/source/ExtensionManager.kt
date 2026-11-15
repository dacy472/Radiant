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
import com.radiant.webtoon.core.settings.StoredPreferenceValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/**
 * Mihon-style manager for the full extension lifecycle:
 * - Maintains [installedExtensions] StateFlow of active sources (via [ExtensionLoader.loadExtensions])
 * - Fetches remote repos ([findAvailableExtensions] / index.pb)
 * - Compares remote versionCodes against installed builds for updates
 * - Filters untrusted extensions by APK signature hashes ([ExtensionSignatureTrust])
 * - Installs/uninstalls and syncs PackageManager installs into Room for Installed Sources UI
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
    private val signatureTrust = ExtensionSignatureTrust(context)
    private val extensionInstaller by lazy { ExtensionInstaller(context, extensionLoader) }

    // Active sources loaded in memory (Mihon: installed extension sources)
    private val _installedExtensions = MutableStateFlow<List<Source>>(emptyList())
    val installedExtensions: StateFlow<List<Source>> = _installedExtensions

    private val _hideNsfw = MutableStateFlow(true)

    // Available extensions from all repos (not yet installed)
    private val _availableExtensions = MutableStateFlow<List<RepoExtensionEntry>>(emptyList())
    val availableExtensions: StateFlow<List<RepoExtensionEntry>> = _availableExtensions

    /** Whitelisted extensions with a newer remote versionCode than the installed build. */
    private val _availableUpdates = MutableStateFlow<List<RepoExtensionEntry>>(emptyList())
    val availableUpdates: StateFlow<List<RepoExtensionEntry>> = _availableUpdates

    /** True while [updateAllAvailable] is applying pending updates (shared by all UI entry points). */
    private val _isUpdatingAllExtensions = MutableStateFlow(false)
    val isUpdatingAllExtensions: StateFlow<Boolean> = _isUpdatingAllExtensions

    // Installed extension infos (from PackageManager scan)
    private val _installedExtensionInfos = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installedExtensionInfos: StateFlow<List<ExtensionInfo>> = _installedExtensionInfos

    /** Packages present on-device but blocked until signature trust is granted. */
    private val _untrustedExtensions = MutableStateFlow<List<Extension.Untrusted>>(emptyList())
    val untrustedExtensions: StateFlow<List<Extension.Untrusted>> = _untrustedExtensions

    // Dedicated source registry for reliable instance retrieval
    private val sourceRegistry = ConcurrentHashMap<String, Source>()
    private val builtInSourceIds = ConcurrentHashMap.newKeySet<String>()
    private val loadMutex = Mutex()
    private val isRefreshingRepos = AtomicBoolean(false)

    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRepoRefreshing = MutableStateFlow(false)

    private val _trustedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _managedPackages = MutableStateFlow<Set<String>>(emptySet())

    private val _advancedMode = MutableStateFlow(false)
    private val _sourceLangs = MutableStateFlow(setOf("en"))

    private val _baseUrlOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val baseUrlOverrides: StateFlow<Map<String, String>> = _baseUrlOverrides

    private companion object {
        const val DEFAULT_REPO_URL =
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb"

        /** Bump when cache format or repo URL changes — clears stale JSON stub caches. */
        const val EXTENSION_CACHE_VERSION = 5

        /**
         * Bump to make every install pull the newest build of each installed extension from the
         * live repo once, even with auto-update off (library and progress are untouched).
         */
        const val LIBRARY_REFRESH_VERSION = 1
        private const val LIBRARY_REFRESH_PREF = "library_refresh_version"

        /** A catalog fetched live within this window is reused instead of refetching. */
        private const val LIVE_CATALOG_MAX_AGE_MS = 15 * 60 * 1000L

        private val LEGACY_REPO_URLS = setOf(
            "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json",
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
        )

        /** Multi-lang ("all") packs are allowed; they include English sources. */
        private val ENABLED_EXTENSION_LANGS = setOf("en", "all")

        const val FLAMECOMICS_PACKAGE = "eu.kanade.tachiyomi.extension.en.flamecomics"
        const val FLAMECOMICS_MIN_EXTENSION_LIB = "1.6"
        private const val FLAMECOMICS_REPAIR_PREF = "flamecomics_v51_repair_done"
    }

    /** Wall-clock time [_availableExtensions] was last replaced by a live repo fetch. */
    @Volatile
    private var liveCatalogAt = 0L

    private fun publishLiveCatalog(entries: List<RepoExtensionEntry>) {
        _availableExtensions.value = entries
        liveCatalogAt = System.currentTimeMillis()
    }

    private fun computeUpdatesFor(
        remote: List<RepoExtensionEntry>,
        installed: List<ExtensionInfo>,
    ): List<RepoExtensionEntry> = computeExtensionUpdates(
        remoteEntries = remote,
        installedVersionCodes = installed.associate { it.packageName to it.versionCode },
        installedLibVersions = installed.associate { it.packageName to it.extensionLibVersion },
    )

    private fun isAdvancedMode(): Boolean = _advancedMode.value

    fun isExtensionLanguageEnabled(lang: String?): Boolean {
        val normalized = lang?.lowercase()?.trim().orEmpty()
        if (normalized.isEmpty()) return true
        if (isAdvancedMode()) {
            return normalized in ExtensionAllowlist.ADVANCED_EXTENSION_LANGS
        }
        return normalized in ENABLED_EXTENSION_LANGS
    }

    fun isSourceLanguageEnabled(lang: String): Boolean {
        val normalized = lang.lowercase().trim()
        if (normalized.isEmpty()) return true
        if (isAdvancedMode()) {
            return normalized in _sourceLangs.value || normalized == "all"
        }
        return normalized in setOf("en")
    }

    fun isPackageAllowed(packageName: String): Boolean =
        ExtensionAllowlist.isAllowed(packageName, advancedMode = isAdvancedMode())

    private fun filterAllowedRepoEntries(entries: List<RepoExtensionEntry>): List<RepoExtensionEntry> =
        entries.filter { ExtensionAllowlist.isAllowedRepoEntry(it) }

    // init MUST run after all StateFlows above — launching earlier races and NPEs on _isLoading
    init {
        // Mihon-style: populate installedExtensions StateFlow from ExtensionLoader.loadExtensions
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
                applyPinnedSources(pinnedIds)
            }
        }

        scope.launch(Dispatchers.IO) {
            settingsManager.extensionAdvancedMode.collect { enabled ->
                _advancedMode.value = enabled
                loadInstalledExtensions(force = true)
            }
        }

        scope.launch(Dispatchers.IO) {
            settingsManager.extensionSourceLangs.collect { langs ->
                _sourceLangs.value = langs
                if (_installedExtensions.value.isNotEmpty() || _installedExtensionInfos.value.isNotEmpty()) {
                    loadInstalledExtensions(force = true)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            settingsManager.sourceBaseUrlOverrides.collect { overrides ->
                _baseUrlOverrides.value = overrides
                applyBaseUrlOverrides(overrides)
            }
        }
    }

    suspend fun togglePin(sourceId: String) {
        settingsManager.togglePinnedSource(sourceId)
    }

    /** Apply persisted pin state to in-memory sources (survives extension reloads). */
    private suspend fun applyPinnedSources(pinnedIds: Set<String>? = null) {
        val pins = pinnedIds ?: settingsManager.pinnedSources.first()
        val seen = mutableSetOf<Source>()
        for (source in sourceRegistry.values) {
            if (seen.add(source)) {
                source.isPinned = pins.contains(source.id)
            }
        }
        for (source in _installedExtensions.value) {
            if (seen.add(source)) {
                source.isPinned = pins.contains(source.id)
            }
        }
        if (_installedExtensions.value.isNotEmpty()) {
            _installedExtensions.value = _installedExtensions.value.toList()
        }
    }

    // ─── Repository management ───

    suspend fun addRepo(url: String) = withContext(Dispatchers.IO) {
        try {
            // Prefer the protobuf index (Mihon format); fall back to the URL as typed for
            // third-party repos that still publish a real JSON index.
            val pbUrl = normalizeRepoIndexUrl(url)
            val (resolvedUrl, index) = try {
                pbUrl to extensionLoader.fetchRepoIndex(pbUrl)
            } catch (e: Exception) {
                if (pbUrl == url.trim()) throw e
                url.trim() to extensionLoader.fetchRepoIndex(url.trim())
            }
            repoDao.insert(ExtensionRepoEntity(
                baseUrl = resolvedUrl,
                name = index.name,
                shortName = index.description
            ))
            Log.d(tag, "Added repo: ${index.name} ($resolvedUrl)")
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

    /**
     * Mihon-style remote catalog fetch:
     * - pulls Keiyoushi [index.pb] via [ExtensionLoader.fetchRepoIndex]
     * - keeps language-enabled entries
     * - compares remote versionCode against installed builds → [availableUpdates]
     *
     * Returns [Extension.Available] models for the UI.
     */
    suspend fun findAvailableExtensions(repoUrl: String? = null): List<Extension.Available> =
        withContext(Dispatchers.IO) {
            ensureDefaultRepo()
            val url = repoUrl ?: resolveRepoUrlForUpdates()
            try {
                val index = extensionLoader.fetchRepoIndex(url)
                val processed = index.extensions.map { it.withRepoContext(url) }
                val languageFiltered = filterAllowedRepoEntries(processed)
                    .filter { isExtensionLanguageEnabled(it.lang) }
                    .distinctBy { it.id }

                if (languageFiltered.isNotEmpty()) publishLiveCatalog(languageFiltered)

                val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
                try {
                    val gson = com.google.gson.Gson()
                    prefs.edit()
                        .putString("cached_extensions", gson.toJson(languageFiltered))
                        .putInt("cache_version", EXTENSION_CACHE_VERSION)
                        .apply()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to cache findAvailableExtensions result", e)
                }

                val installed = _installedExtensionInfos.value.ifEmpty {
                    mergeInstalledExtensionInfos(
                        extensionLoader.scanLocalExtensions(),
                        extensionLoader.scanInstalledExtensions(),
                    )
                }
                _availableUpdates.value = computeUpdatesFor(languageFiltered, installed)

                Log.i(
                    tag,
                    "findAvailableExtensions: ${languageFiltered.size} available, " +
                        "${_availableUpdates.value.size} update(s), langs=" +
                        languageFiltered.mapNotNull { it.lang }.toSet(),
                )
            } catch (e: Exception) {
                Log.e(tag, "findAvailableExtensions failed for $url", e)
            }
            getAvailableAsExtensions()
        }

    /**
     * Compare installed builds against the live/cached repo index and publish extensions
     * whose remote [RepoExtensionEntry.versionCode] exceeds the installed build.
     */
    suspend fun refreshAvailableUpdates(repoUrl: String? = null) = withContext(Dispatchers.IO) {
        try {
            val remote = resolveRemoteExtensionEntries(repoUrl)
            val updates = computeUpdatesFor(remote, _installedExtensionInfos.value)
            _availableUpdates.value = updates
            Log.d(tag, "Available updates: ${_availableUpdates.value.size}")
        } catch (e: Exception) {
            Log.w(tag, "Failed to check extension updates (keeping prior state): ${e.message}")
        }
    }

    /**
     * Background/cold-start entry point: checks for extension updates and either applies
     * them silently or only publishes [availableUpdates] for badge UI, per user preference.
     */
    suspend fun runBackgroundExtensionUpdateCheck(repoUrl: String? = null) = withContext(Dispatchers.IO) {
        prepareForUpdateCheck()
        refreshRemoteCatalogFromRepos(repoUrl)
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val libraryRefreshDue = storedInt(prefs, LIBRARY_REFRESH_PREF, 0) < LIBRARY_REFRESH_VERSION
        val autoUpdate = settingsManager.autoUpdateExtensions.first()
        if (libraryRefreshDue) {
            refreshExtensionLibrary(repoUrl)
        } else if (autoUpdate) {
            checkAndApplyUpdatesSilently(repoUrl)
        } else {
            refreshAvailableUpdates(repoUrl)
        }
        repairKnownBrokenExtensions()
    }

    /**
     * One-time pass (see [LIBRARY_REFRESH_VERSION]): update every installed extension to the newest
     * build in the live repo and re-arm the Flame Comics repair. Marked done only when the live
     * catalog was reachable and nothing failed, so a flaky first launch retries next time.
     */
    private suspend fun refreshExtensionLibrary(repoUrl: String?) {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        Log.i(tag, "Refreshing installed extensions from the live repo (v$LIBRARY_REFRESH_VERSION)")
        prefs.edit().remove(FLAMECOMICS_REPAIR_PREF).apply()
        liveCatalogAt = 0L
        val remote = resolveRemoteExtensionEntries(repoUrl)
        if (remote.isEmpty() || System.currentTimeMillis() - liveCatalogAt > LIVE_CATALOG_MAX_AGE_MS) {
            Log.w(tag, "Library refresh postponed: live repo catalog unavailable")
            refreshAvailableUpdates(repoUrl)
            return
        }
        checkAndApplyUpdatesSilently(repoUrl)
        if (_availableUpdates.value.isEmpty()) {
            prefs.edit().putInt(LIBRARY_REFRESH_PREF, LIBRARY_REFRESH_VERSION).apply()
            Log.i(tag, "Extension library refresh complete")
        }
        loadInstalledExtensions(force = true)
    }

    /**
     * One-time and version-gated repairs for extensions with known broken DTOs in older APKs.
     */
    suspend fun repairKnownBrokenExtensions() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val flameInstalled = _installedExtensionInfos.value.find { it.packageName == FLAMECOMICS_PACKAGE }
        val needsOneTimeRepair = !storedBoolean(prefs, FLAMECOMICS_REPAIR_PREF, false)
        // Keiyoushi restarted versionCode with the lib-1.6 rewrite, so gate on the lib, not the code.
        val belowMinimum = flameInstalled != null &&
            isNewerExtensionLib(FLAMECOMICS_MIN_EXTENSION_LIB, flameInstalled.extensionLibVersion)

        if (flameInstalled == null) return@withContext
        if (!needsOneTimeRepair && !belowMinimum) return@withContext

        val repaired = forceReinstallExtension(FLAMECOMICS_PACKAGE)
        if (repaired) {
            prefs.edit().putBoolean(FLAMECOMICS_REPAIR_PREF, true).apply()
            loadInstalledExtensions(force = true)
        }
    }

    /**
     * Force-download and reinstall an extension from the live repo index, even when versionCode
     * comparison would skip it. Used to recover from stale/corrupt local APKs.
     */
    suspend fun forceReinstallExtension(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = resolveExtensionEntry(packageName)
                ?: return@withContext false
            installExtensionEntry(entry)
            hotReloadExtension(packageName)
            true
        } catch (e: Exception) {
            Log.w(tag, "Force reinstall failed for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Repair a source after a kotlinx.serialization failure (stale extension bytecode).
     */
    suspend fun repairSourceAfterSerializationFailure(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        val adapter = getSource(sourceId) as? TachiyomiSourceAdapter ?: return@withContext false
        val packageName = when {
            adapter.icon.startsWith("pkg:") -> adapter.icon.removePrefix("pkg:")
            adapter.icon.startsWith("apk:") -> java.io.File(adapter.icon.removePrefix("apk:")).nameWithoutExtension
            else -> return@withContext false
        }
        if (ExtensionAllowlist.slugOf(packageName) != "flamecomics") return@withContext false
        forceReinstallExtension(packageName)
    }

    /**
     * Background path: download, overwrite, persist versionCode, and hot-reload each update
     * without toggling [isLoading] or surfacing errors to the UI.
     */
    suspend fun checkAndApplyUpdatesSilently(repoUrl: String? = null) = withContext(Dispatchers.IO) {
        try {
            prepareForUpdateCheck()
            val updates = computeUpdatesFor(resolveRemoteExtensionEntries(repoUrl), _installedExtensionInfos.value)
            if (updates.isEmpty()) {
                _availableUpdates.value = emptyList()
                return@withContext
            }

            val failed = mutableListOf<RepoExtensionEntry>()
            for (entry in updates) {
                try {
                    applyExtensionUpdateSilently(entry)
                    Log.d(tag, "Silently applied update for ${entry.id} (v${entry.versionCode})")
                } catch (e: Exception) {
                    Log.w(tag, "Silent update failed for ${entry.id}: ${e.message}")
                    failed.add(entry)
                }
            }
            _availableUpdates.value = failed
            Log.d(tag, "Silent update pass finished; ${failed.size} pending")
        } catch (e: Exception) {
            Log.w(tag, "Silent extension update pass failed (ignored): ${e.message}")
        }
    }

    private suspend fun applyExtensionUpdateSilently(entry: RepoExtensionEntry) {
        installExtensionEntry(entry)
        hotReloadExtension(entry.id)
    }

    /**
     * Live-fetch the repo index (pb first, json fallback via [ExtensionLoader.fetchRepoIndex]) from
     * [repoUrl] or, when null, from ALL registered repos (default repo if none). One failing repo is
     * logged and skipped. Entries are merged by package id keeping the highest versionCode and
     * published to [_availableExtensions] so the update compare never relies on a stale cache.
     */
    private suspend fun refreshRemoteCatalogFromRepos(repoUrl: String? = null) {
        try {
            ensureDefaultRepo()
            val urls = if (repoUrl != null) {
                listOf(repoUrl)
            } else {
                repoDao.getAll().map { it.baseUrl }.ifEmpty { listOf(resolveRepoUrlForUpdates()) }
            }
            val merged = collectRepoEntries(
                repoUrls = urls,
                fetch = { url -> extensionLoader.fetchRepoIndex(url).extensions.map { it.withRepoContext(url) } },
                onError = { url, e -> Log.w(tag, "Update check: failed to fetch repo $url: ${e.message}") },
            )
            if (merged.isNotEmpty()) {
                publishLiveCatalog(filterAllowedRepoEntries(merged))
            }
        } catch (e: Exception) {
            Log.w(tag, "Update check: remote catalog refresh failed (using cache): ${e.message}")
        }
    }

    /** Refresh installed extension version codes before a background update check. */
    suspend fun prepareForUpdateCheck() = withContext(Dispatchers.IO) {
        val systemInfos = extensionLoader.scanInstalledExtensions()
        val localInfos = extensionLoader.scanLocalExtensions()
        val installedInfos = mergeInstalledExtensionInfos(localInfos, systemInfos)
        _installedExtensionInfos.value = ExtensionAllowlist.dedupeBySlugPreferEn(
            installedInfos.filter { isPackageAllowed(it.packageName) },
            packageSelector = { it.packageName },
            advancedMode = isAdvancedMode(),
        )
    }

    private suspend fun resolveRepoUrlForUpdates(): String {
        ensureDefaultRepo()
        val repos = repoDao.getAll()
        if (repos.isNotEmpty()) return repos.first().baseUrl
        val configured = settingsManager.defaultRepoUrl.first().trim()
        return configured.ifBlank { DEFAULT_REPO_URL }
    }

    /**
     * Remote extension catalog for update checks. Live first: a catalog fetched from the repos in
     * the last [LIVE_CATALOG_MAX_AGE_MS] is reused, otherwise every repo's index.pb is fetched.
     * The in-memory list and the persisted cache are only fallbacks when the network fails, so
     * a months-old cache can no longer hide newer extension builds.
     */
    private suspend fun resolveRemoteExtensionEntries(repoUrl: String?): List<RepoExtensionEntry> {
        val inMemory = _availableExtensions.value
        val usable = { entries: List<RepoExtensionEntry> ->
            entries.isNotEmpty() && !isStaleStubCache(entries) && entries.hasAttachedRepoUrls()
        }
        val liveIsFresh = System.currentTimeMillis() - liveCatalogAt <= LIVE_CATALOG_MAX_AGE_MS
        if (liveIsFresh && usable(inMemory)) return inMemory

        ensureDefaultRepo()
        val urls = if (repoUrl != null) {
            listOf(repoUrl)
        } else {
            repoDao.getAll().map { it.baseUrl }.ifEmpty { listOf(resolveRepoUrlForUpdates()) }
        }
        val fetched = collectRepoEntries(
            repoUrls = urls,
            fetch = { url -> extensionLoader.fetchRepoIndex(url).extensions.map { it.withRepoContext(url) } },
            onError = { url, e -> Log.w(tag, "Failed to fetch repo $url for update check: ${e.message}") },
        )
        if (fetched.isNotEmpty()) {
            val allowed = filterAllowedRepoEntries(fetched)
            publishLiveCatalog(allowed)
            return allowed
        }

        if (usable(inMemory)) return inMemory
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val cachedJson = prefs.getString("cached_extensions", null) ?: return emptyList()
        return try {
            val gson = com.google.gson.Gson()
            val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
            val cached: List<RepoExtensionEntry> = gson.fromJson(cachedJson, listType)
            if (usable(cached)) {
                filterAllowedRepoEntries(cached).also { _availableExtensions.value = it }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse cached extensions for update check", e)
            emptyList()
        }
    }

    suspend fun refreshAvailable() {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()

        migrateExtensionCacheIfNeeded(prefs)

        // Step A: Immediately read the cached JSON string, parse it, and emit it to the UI
        val cachedJson = prefs.getString("cached_extensions", null)
        if (cachedJson != null) {
            try {
                val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
                val cachedExtensions: List<RepoExtensionEntry> = gson.fromJson(cachedJson, listType)
                if (!isStaleStubCache(cachedExtensions) && cachedExtensions.hasAttachedRepoUrls()) {
                    _availableExtensions.value = filterAllowedRepoEntries(cachedExtensions)
                    Log.d(tag, "Loaded ${_availableExtensions.value.size} extensions from cache")
                } else {
                    Log.w(tag, "Ignoring stale stub extension cache (${cachedExtensions.size} entries)")
                    prefs.edit().remove("cached_extensions").apply()
                }
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
                        val processedExtensions = index.extensions.map { it.withRepoContext(url) }
                        allEntries.addAll(processedExtensions)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to fetch repo: $url", e)
                    }
                }

                if (allEntries.isNotEmpty()) {
                    // Same extension can appear in multiple repos — keep first, unique by id
                    publishLiveCatalog(filterAllowedRepoEntries(mergeRepoEntriesKeepingHighest(allEntries)))
                    try {
                        val newJson = gson.toJson(_availableExtensions.value)
                        prefs.edit()
                            .putString("cached_extensions", newJson)
                            .putInt("cache_version", EXTENSION_CACHE_VERSION)
                            .apply()
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
                _installedExtensionInfos.value = mergeInstalledExtensionInfos(localInfos, systemInfos)

                // Reload sources only when fingerprint changed (avoid DEX reload on every UI refresh)
                loadInstalledExtensions(force = false)
                refreshAvailableUpdates(repoUrls.firstOrNull())
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
        if (storedBoolean(prefs, "first_launch_defaults_installed", false)) return

        val curated = entries
            .filter { ExtensionAllowlist.isAllowedRepoEntry(it) && isPackageAllowed(it.id) }
        val bySlug = ExtensionAllowlist.dedupeBySlugPreferEn(
            curated,
            packageSelector = { it.id },
        ).associateBy { ExtensionAllowlist.slugOf(it.id) }
        val defaultEntries = ExtensionAllowlist.DEFAULT_INSTALL_SLUGS.mapNotNull { bySlug[it] }
            .ifEmpty { curated.take(6) }

        Log.i(tag, "First launch: Auto-installing ${defaultEntries.size} curated extensions...")
        var installedAny = false

        for (entry in defaultEntries) {
            val id = entry.id
            try {
                installExtensionEntry(entry)
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
        val entry = resolveExtensionEntry(extensionId)
            ?: throw Exception("Extension '$extensionId' not found")
        installExtensionEntry(entry)
        loadInstalledExtensions(force = true)
    }

    /**
     * Download and install every extension in [availableUpdates], then clear successful entries.
     */
    suspend fun updateAllAvailable(): UpdateAllResult = withContext(Dispatchers.IO) {
        val pending = _availableUpdates.value.toList()
        if (pending.isEmpty()) {
            return@withContext UpdateAllResult(0, 0, emptyList())
        }

        if (!_isUpdatingAllExtensions.compareAndSet(false, true)) {
            return@withContext UpdateAllResult(0, 0, emptyList())
        }

        var succeeded = 0
        val failures = mutableListOf<Pair<String, String>>()

        try {
            // Sequential; same apply path as the silent background updater.
            for (entry in pending) {
                try {
                    applyExtensionUpdateSilently(entry)
                    succeeded++
                } catch (e: Exception) {
                    Log.e(tag, "Failed to update ${entry.id}", e)
                    failures.add(entry.id to (e.message ?: e.javaClass.simpleName))
                }
            }

            val failedIds = failures.map { it.first }.toSet()
            _availableUpdates.value = pending.filter { it.id in failedIds }

            prepareForUpdateCheck()
            loadInstalledExtensions(force = true)
        } finally {
            _isUpdatingAllExtensions.value = false
        }

        UpdateAllResult(
            succeeded = succeeded,
            failed = failures.size,
            failures = failures,
        )
    }

    private suspend fun resolveExtensionEntry(extensionId: String): RepoExtensionEntry? {
        val local = _availableUpdates.value.find { it.id == extensionId }
            ?: _availableExtensions.value.find { it.id == extensionId }
        if (local != null && !local.repoUrl.isNullOrBlank()) return local

        val repos = repoDao.getAll()
        val repoUrls = if (repos.isEmpty()) {
            listOf(DEFAULT_REPO_URL)
        } else {
            repos.map { it.baseUrl }
        }
        for (url in repoUrls) {
            try {
                val index = extensionLoader.fetchRepoIndex(url)
                val found = index.extensions.find { it.id == extensionId }?.withRepoContext(url)
                if (found != null) return found
            } catch (e: Exception) {
                Log.e(tag, "Failed to look up $extensionId in $url", e)
            }
        }
        return local?.withRepoUrl(resolveRepoUrlForUpdates())
    }

    private suspend fun installExtensionEntry(entry: RepoExtensionEntry) {
        val extensionId = entry.id

        if (!ExtensionAllowlist.isAllowedRepoEntry(entry.name, extensionId)) {
            throw Exception(
                "This extension is not in Radiant's allowed source list (${entry.name}).",
            )
        }

        if (!isExtensionLanguageEnabled(entry.lang)) {
            throw Exception("Only English or multi-language (all) extensions can be installed")
        }

        // Parent repo is attached at fetch time. If a cached entry lost it, use the configured repo
        // so download never fails with "has no repository URL" when a file name is known.
        val repoUrl = entry.installableRepoUrl() ?: resolveRepoUrlForUpdates()
        val resolvedEntry = entry.withRepoUrl(repoUrl)
        val installedState = extensionInstaller.readInstalledState(extensionId)
        val useSystemInstaller = ExtensionInstallRules.useSystemInstaller(
            installedState,
            systemInstalled = extensionLoader.isPackageInstalled(extensionId),
        )

        Log.i(tag, "Installing extension: $extensionId (systemInstaller=$useSystemInstaller)")
        val downloadedFile = extensionInstaller.downloadAndVerify(resolvedEntry, repoUrl, installedState)

        if (useSystemInstaller) {
            // Real Android package: update in place through PackageInstaller (session API).
            try {
                extensionInstaller.installIntoSystem(downloadedFile, entry, installedState)
            } finally {
                downloadedFile.delete()
            }
            persistInstalledRow(entry, trusted = true)
            extensionLoader.invalidateLoadedExtension(entry.id)
            _availableUpdates.update { updates -> updates.filter { it.id != extensionId } }
            refreshTrustCache()
            Log.i(tag, "Successfully updated system extension: $extensionId")
            return
        }

        val destFile = ExtensionApkPaths.packageApkFile(extensionLoader.extensionsDir, entry.id)
        val staged = File(extensionLoader.extensionsDir, "${entry.id}.apk.staging")
        try {
            if (staged.exists()) staged.delete()
            downloadedFile.copyTo(staged, overwrite = true)
        } finally {
            downloadedFile.delete()
        }

        try {
        val decision = extensionLoader.evaluateStagedApk(staged, expectedPackage = entry.id, expectedVersionCode = entry.versionCode)
        when (decision) {
            is ExtensionInstallDecision.Rejected -> {
                staged.delete()
                throw Exception(decision.reason)
            }
            is ExtensionInstallDecision.UnknownSigner -> {
                val previous = extensionLoader.scanLocalExtensions().find { it.packageName == entry.id }
                if (previous != null && extensionLoader.isApkTrustedForExecution(previous)) {
                    staged.delete()
                    throw Exception("Update rejected: signer is not trusted. The previous APK was kept.")
                }
                extensionLoader.commitStagedApk(staged, destFile)
                persistInstalledRow(entry, trusted = false)
                _untrustedExtensions.update { current ->
                    current.filterNot { it.pkgName == entry.id } + Extension.Untrusted(
                        id = entry.id,
                        name = entry.name,
                        version = entry.version,
                        nsfw = entry.nsfw != 0,
                        pkgName = entry.id,
                        versionCode = entry.versionCode,
                        signatureHash = decision.signatureHash,
                    )
                }
                extensionLoader.invalidateLoadedExtension(entry.id)
                refreshTrustCache()
                throw Exception("Installed ${entry.name} but it is not trusted. Approve the signer to load it.")
            }
            is ExtensionInstallDecision.Trusted -> {
                // In-place update: same signing key as the stored APK, or an official key.
                // Unsigned local APKs are tolerated only in debug builds.
                extensionInstaller.requireSignatureContinuity(
                    staged,
                    installedState,
                    official = decision.official,
                    allowUnsignedDebug = com.radiant.webtoon.BuildConfig.DEBUG,
                )
                if (!decision.official && decision.signatureHash.isNotBlank()) {
                    signatureTrust.trust(decision.packageName, decision.versionCode, decision.signatureHash)
                }
                extensionLoader.commitStagedApk(staged, destFile)
                persistInstalledRow(entry, trusted = true)
            }
        }
        } finally {
            // Never leave a half-installed staging file behind (no-op after a successful commit).
            if (staged.exists()) staged.delete()
        }

        extensionLoader.invalidateLoadedExtension(entry.id)

        _availableUpdates.update { updates -> updates.filter { it.id != extensionId } }

        refreshTrustCache()
        Log.i(tag, "Successfully installed extension: $extensionId")
    }

    private suspend fun persistInstalledRow(entry: RepoExtensionEntry, trusted: Boolean) {
        val existing = installedDao.getById(entry.id)
        installedDao.insert(
            InstalledExtensionEntity(
                id = entry.id,
                name = entry.name,
                isTrusted = trusted,
                versionCode = entry.versionCode,
                dateInstalled = existing?.dateInstalled ?: System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Remove installed/cached extensions that are not on the strict allowlist.
     * Idempotent — safe to run on every cold start before loading sources.
     */
    suspend fun purgeNonWhitelistedExtensions(): List<String> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            purgeNonWhitelistedExtensionsInternal()
        }
    }

    private suspend fun purgeNonWhitelistedExtensionsInternal(): List<String> {
        val localInfos = extensionLoader.scanLocalExtensions()
        val systemInfos = extensionLoader.scanInstalledExtensions()
        val merged = mergeInstalledExtensionInfos(localInfos, systemInfos)
        val dbRows = installedDao.getAll()

        val displayNameOf: (String) -> String? = { id ->
            merged.find { it.packageName == id }?.name
                ?: dbRows.find { it.id == id }?.name
        }

        val toPurge = ExtensionAllowlist.packagesToPurge(
            merged.map { it.packageName } + dbRows.map { it.id },
            displayNameOf,
        )
        if (toPurge.isEmpty()) return emptyList()

        for (pkg in toPurge) {
            Log.i(tag, "Purging non-whitelisted extension: $pkg (${displayNameOf(pkg)})")
            removeExtensionLocally(pkg)
        }

        _installedExtensionInfos.update { list ->
            list.filterNot { it.packageName in toPurge }
        }
        _installedExtensions.update { current ->
            current.filterNot { source ->
                source is TachiyomiSourceAdapter && toPurge.any { pkg -> source.icon.contains(pkg) }
            }
        }
        _availableUpdates.update { updates ->
            updates.filterNot { it.id in toPurge }
        }

        pruneNonWhitelistedExtensionCache()

        Log.i(tag, "Purged ${toPurge.size} non-whitelisted extensions: ${toPurge.joinToString()}")
        return toPurge
    }

    private fun pruneNonWhitelistedExtensionCache() {
        val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
        val cachedJson = prefs.getString("cached_extensions", null) ?: return
        try {
            val gson = com.google.gson.Gson()
            val listType = object : com.google.gson.reflect.TypeToken<List<RepoExtensionEntry>>() {}.type
            val cached: List<RepoExtensionEntry> = gson.fromJson(cachedJson, listType)
            val filtered = filterAllowedRepoEntries(cached)
                .filter { it.installableRepoUrl() != null }
            if (filtered.size != cached.size) {
                prefs.edit().putString("cached_extensions", gson.toJson(filtered)).apply()
                _availableExtensions.value = filtered
                Log.i(tag, "Pruned ${cached.size - filtered.size} extension cache entries")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to prune extension cache during purge", e)
        }
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
     *
     * - PackageManager-installed APKs: deletes any private copy, then returns an
     *   [Intent.ACTION_DELETE] intent so the OS prompts the user to uninstall.
     *   Local DB/state is reconciled later via [syncSystemInstalledExtensions]
     *   (e.g. when the Extensions screen resumes after the system dialog).
     * - Privately loaded APKs only: removes the local file and DB/state immediately.
     *
     * @return system uninstall [Intent] when the package is installed on-device; null if
     *   only a private copy was removed.
     */
    suspend fun uninstallExtension(packageName: String): Intent? = withContext(Dispatchers.IO) {
        Log.i(tag, "Uninstalling extension: $packageName")

        // Always drop any privately copied APK under filesDir
        extensionLoader.deleteLocalExtension(packageName)

        if (extensionLoader.isPackageInstalled(packageName)) {
            // Keep DB/in-memory state until the OS uninstall finishes; sync on resume.
            Log.i(tag, "Package $packageName is system-installed; returning OS uninstall intent")
            return@withContext getSystemUninstallIntent(packageName)
        }

        removeExtensionLocally(packageName)
        refreshTrustCache()

        _installedExtensionInfos.update { list ->
            list.filterNot { it.packageName == packageName }
        }

        lastLoadedFingerprint = ""
        loadInstalledExtensions(force = true)
        refreshAvailableUpdates()

        Log.i(tag, "Extension $packageName fully uninstalled and unloaded (local-only).")
        null
    }

    data class ForceResetResult(
        val deletedApkCount: Int,
        val reinstalledCount: Int,
    )

    /**
     * Developer reset: delete all local extension APKs, clear extension prefs/cache,
     * wipe the installed_extensions table, then fetch the live repo and reinstall defaults.
     */
    suspend fun forceResetExtensions(): ForceResetResult = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            Log.w(tag, "Force-resetting all local extensions")

            val deletedApkCount = extensionLoader.clearAllDownloadedBundles()
            installedDao.deleteAll()

            val prefs = context.getSharedPreferences("extension_cache", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("cached_extensions")
                .remove("first_launch_defaults_installed")
                .remove(FLAMECOMICS_REPAIR_PREF)
                .remove("flamecomics_v50_repair_done")
                .putInt("cache_version", EXTENSION_CACHE_VERSION)
                .apply()

            val preservedBuiltIns = builtInSourceIds.mapNotNull { sourceRegistry[it] }
            sourceRegistry.clear()
            preservedBuiltIns.forEach { sourceRegistry[it.id] = it }

            _installedExtensions.value = preservedBuiltIns.toList()
            _installedExtensionInfos.value = emptyList()
            _availableExtensions.value = emptyList()
            _availableUpdates.value = emptyList()
            _trustedPackages.value = emptySet()
            _managedPackages.value = emptySet()
            _installedCount.value = preservedBuiltIns.size
            lastLoadedFingerprint = ""

            ensureDefaultRepo()
            val repoUrl = resolveRepoUrlForUpdates()
            val index = extensionLoader.fetchRepoIndex(repoUrl)
            val entries = filterAllowedRepoEntries(
                index.extensions.map { it.withRepoContext(repoUrl) },
            )
            publishLiveCatalog(entries)
            try {
                val gson = com.google.gson.Gson()
                prefs.edit()
                    .putString("cached_extensions", gson.toJson(entries))
                    .putInt("cache_version", EXTENSION_CACHE_VERSION)
                    .apply()
            } catch (e: Exception) {
                Log.w(tag, "Failed to cache extensions after reset", e)
            }

            installDefaultExtensionsIfFirstLaunch(entries)
            refreshTrustCache()
            loadInstalledExtensions(force = true)
            repairKnownBrokenExtensions()

            val reinstalledCount = installedDao.getAll().size
            Log.i(
                tag,
                "Extension force-reset complete: removed $deletedApkCount APK(s), " +
                    "reinstalled $reinstalledCount extension(s)",
            )

            ForceResetResult(
                deletedApkCount = deletedApkCount,
                reinstalledCount = reinstalledCount,
            )
        }
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
        val info = _installedExtensionInfos.value.find { it.packageName == packageName }
        if (existing != null) {
            installedDao.setTrusted(packageName, true)
        } else {
            installedDao.insert(
                InstalledExtensionEntity(
                    id = packageName,
                    name = info?.name ?: packageName,
                    isTrusted = true,
                    versionCode = info?.versionCode ?: 0,
                ),
            )
        }

        // Mihon-style: persist signing-cert trust key when we can read the package
        try {
            val flags = ExtensionSignatureTrust.packageFlags()
            @Suppress("DEPRECATION")
            val pkgInfo = context.packageManager.getPackageInfo(packageName, flags)
            val signatures = signatureTrust.getSignatures(pkgInfo)
            if (signatures.isNotEmpty()) {
                signatureTrust.trust(
                    packageName,
                    ExtensionSignatureTrust.packageVersionCode(pkgInfo),
                    signatures.last(),
                )
            } else if (!info?.signatureHash.isNullOrBlank()) {
                signatureTrust.trust(packageName, info!!.versionCode, info.signatureHash!!)
            }
        } catch (e: Exception) {
            info?.signatureHash?.let { hash ->
                signatureTrust.trust(packageName, info.versionCode, hash)
            }
            Log.w(tag, "Could not read package signatures while trusting $packageName: ${e.message}")
        }

        _untrustedExtensions.update { list -> list.filterNot { it.pkgName == packageName } }
        refreshTrustCache()
        loadInstalledExtensions(force = true)
    }

    /** Trust an [Extension.Untrusted] by package + signature hash (Mihon ExtensionManager.trust). */
    suspend fun trustUntrustedExtension(extension: Extension.Untrusted) = withContext(Dispatchers.IO) {
        signatureTrust.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
        trustExtension(extension.pkgName)
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
        val all = installedDao.getAll()
        _managedPackages.value = all.map { it.id }.toSet()
        _trustedPackages.value = all.filter { it.isTrusted }.map { it.id }.toSet()
    }

    /**
     * Ensures a default repo row exists. Must NOT call [refreshAvailable] —
     * that used to recurse via loadInstalledExtensions → setupDefaultRepo → refreshAvailable
     * and caused an infinite loading/scroll loop on the Extensions tab.
     */
    private suspend fun ensureDefaultRepo() {
        try {
            migrateLegacyRepoUrls()
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

    private suspend fun migrateLegacyRepoUrls() {
        for (legacy in LEGACY_REPO_URLS) {
            val existing = repoDao.getByUrl(legacy) ?: continue
            repoDao.delete(legacy)
            repoDao.insert(
                existing.copy(
                    baseUrl = DEFAULT_REPO_URL,
                    name = existing.name.ifBlank { "Keiyoushi" },
                ),
            )
            Log.i(tag, "Migrated extension repo URL from legacy JSON index to index.pb")
        }
        // Any other Keiyoushi JSON mirror is a stub now; point it at index.pb as well.
        for (repo in repoDao.getAll()) {
            val url = repo.baseUrl
            if (!url.contains("keiyoushi", ignoreCase = true)) continue
            if (!url.endsWith(".json", ignoreCase = true)) continue
            val pbUrl = normalizeRepoIndexUrl(url)
            repoDao.delete(url)
            if (repoDao.getByUrl(pbUrl) == null) repoDao.insert(repo.copy(baseUrl = pbUrl))
            Log.i(tag, "Migrated Keiyoushi JSON repo $url -> $pbUrl")
        }
    }

    private fun migrateExtensionCacheIfNeeded(prefs: android.content.SharedPreferences) {
        val stored = try {
            prefs.all
        } catch (e: ClassCastException) {
            Log.w(tag, "Unreadable extension cache prefs; leaving installed APKs in place", e)
            return
        }
        val raw = stored["cache_version"]
        val version = if (!stored.containsKey("cache_version")) {
            1
        } else {
            StoredPreferenceValues.asInt(raw, EXTENSION_CACHE_VERSION)
        }
        if (raw != null && raw !is Int) {
            prefs.edit().putInt("cache_version", version).apply()
        }
        if (version >= EXTENSION_CACHE_VERSION) return
        prefs.edit()
            .remove("cached_extensions")
            .putBoolean("first_launch_defaults_installed", false)
            .putInt("cache_version", EXTENSION_CACHE_VERSION)
            .apply()
        Log.i(tag, "Cleared extension cache for repo format migration (v$version -> v$EXTENSION_CACHE_VERSION)")
    }

    private fun isStaleStubCache(entries: List<RepoExtensionEntry>): Boolean {
        if (entries.isEmpty()) return false
        if (entries.size > 10) return false
        return entries.all { ExtensionAllowlist.isRepoStub(it.id) }
    }

    private fun List<RepoExtensionEntry>.hasAttachedRepoUrls(): Boolean =
        isNotEmpty() && all { !it.repoUrl.isNullOrBlank() }

    /** Local APKs override system installs; dedupe by package name. */
    private fun mergeInstalledExtensionInfos(
        localInfos: List<ExtensionInfo>,
        systemInfos: List<ExtensionInfo>,
    ): List<ExtensionInfo> {
        val byPackage = linkedMapOf<String, ExtensionInfo>()
        for (info in systemInfos) {
            byPackage.putIfAbsent(info.packageName, info)
        }
        for (info in localInfos) {
            byPackage[info.packageName] = info
        }
        return byPackage.values.toList()
    }

    /**
     * Reload a single extension in memory after a silent background update.
     * Does not touch [_isLoading] — no UI spinners or blocking overlays.
     */
    private suspend fun hotReloadExtension(packageName: String) {
        loadMutex.withLock {
            // Fresh PathClassLoader per version: drop the cached loader + read-only APK copy first.
            // (A loader cannot unload classes, so old Source instances are dropped below, never reused.)
            extensionLoader.invalidateLoadedExtension(packageName)

            val systemInfos = extensionLoader.scanInstalledExtensions()
            val localInfos = extensionLoader.scanLocalExtensions()
            val info = mergeInstalledExtensionInfos(localInfos, systemInfos)
                .find { it.packageName == packageName }

            // Drop every Source instance (and cached memo state) built from the old classes.
            val removedKeys = sourceRegistry.filterValues { source ->
                source is TachiyomiSourceAdapter && source.icon.contains(packageName)
            }.keys.toList()
            removedKeys.forEach { key ->
                sourceRegistry.remove(key)?.let { ChapterMemoCache.clearSource(it.id) }
            }

            if (info == null) {
                _installedExtensionInfos.update { list -> list.filter { it.packageName != packageName } }
                _installedExtensions.update { current ->
                    current.filterNot { it is TachiyomiSourceAdapter && it.icon.contains(packageName) }
                }
                return@withLock
            }

            _installedExtensionInfos.update { list ->
                list.filter { it.packageName != packageName } + info
            }

            if (!_trustedPackages.value.contains(packageName)) {
                _installedExtensions.update { current ->
                    current.filterNot { it is TachiyomiSourceAdapter && it.icon.contains(packageName) }
                }
                return@withLock
            }

            val adapters = loadAdaptersForExtension(info)
            val activeAdapters = adapters.filter { isSourceLanguageEnabled(it.lang) }
            for (adapter in activeAdapters) {
                sourceRegistry[adapter.id] = adapter
                sourceRegistry.putIfAbsent(packageName, adapter)
            }

            _installedExtensions.update { current ->
                ExtensionAllowlist.dedupeSourcesBySlugPreferEn(
                    current.filterNot { it is TachiyomiSourceAdapter && it.icon.contains(packageName) } +
                        activeAdapters,
                    advancedMode = isAdvancedMode(),
                )
            }

            applyPinnedSources()

            refreshExtensionFingerprint()
            refreshBaseUrlRewriteRegistry()
            Log.i(tag, "Hot-reloaded extension in memory: $packageName (v${info.versionCode})")
        }
    }

    /**
     * Targeted reload after an install/update: invalidates the old [dalvik.system.PathClassLoader]
     * and read-only APK copy for [packageName], re-scans it (local + PackageManager), instantiates
     * fresh Sources from a new loader, and republishes [installedExtensions] and
     * [installedExtensionInfos]. No app restart and no full reload of other extensions.
     */
    suspend fun reloadExtension(packageName: String) = withContext(Dispatchers.IO) {
        hotReloadExtension(packageName)
    }

    private suspend fun loadAdaptersForExtension(extInfo: ExtensionInfo): List<TachiyomiSourceAdapter> {
        return try {
            com.radiant.webtoon.core.concurrent.AppWorkQueues.withExtensionLoad {
                val tachiyomiSources = extensionLoader.loadExtensionSources(extInfo)
                val iconUri = if (extInfo.apkPath.startsWith(context.filesDir.absolutePath)) {
                    "apk:${extInfo.apkPath}"
                } else {
                    "pkg:${extInfo.packageName}"
                }
                tachiyomiSources.map { tSource -> wrapTachiyomiSource(tSource, iconUri) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load adapters for ${extInfo.packageName}", e)
            emptyList()
        }
    }

    private fun refreshExtensionFingerprint() {
        val allowedInfos = _installedExtensionInfos.value.filter { isPackageAllowed(it.packageName) }
        val trusted = _trustedPackages.value
        lastLoadedFingerprint = ExtensionInstallRules.buildFingerprint(
            allowedInfos.filter { trusted.contains(it.packageName) }.map(::fingerprintEntry),
            advanced = isAdvancedMode(),
            langs = _sourceLangs.value,
        )
    }

    /** versionCode + APK path/mtime/size so a same-path replace is never skipped by the reload check. */
    private fun fingerprintEntry(info: ExtensionInfo): String {
        val apk = File(info.apkPath)
        return ExtensionInstallRules.fingerprintEntry(
            info.packageName,
            info.versionCode,
            info.apkPath,
            apk.lastModified(),
            apk.length(),
        )
    }

    /**
     * Sync system-installed Tachiyomi extension packages into Room so they reappear
     * in the Installed Sources UI ([getInstalledAsExtensions] requires a DB row).
     *
     * Uses [ExtensionLoader.discoverSystemExtensionPackages] (PackageManager prefix scan),
     * upserts allowlisted packages as trusted, and drops stale tachiyomi-prefix rows that
     * are no longer installed system-wide and have no local APK copy.
     *
     * @return number of packages upserted into the database
     */
    private fun storedInt(
        prefs: android.content.SharedPreferences,
        key: String,
        default: Int,
    ): Int {
        return try {
            val stored = prefs.all
            val value = StoredPreferenceValues.asInt(stored[key], default)
            if (stored.containsKey(key) && stored[key] !is Int) {
                prefs.edit().putInt(key, value).apply()
            }
            value
        } catch (e: ClassCastException) {
            Log.w(tag, "Unreadable pref $key", e)
            prefs.edit().remove(key).apply()
            default
        }
    }

    private fun storedBoolean(
        prefs: android.content.SharedPreferences,
        key: String,
        default: Boolean,
    ): Boolean {
        return try {
            val stored = prefs.all
            val raw = stored[key]
            val value = StoredPreferenceValues.asBoolean(raw) ?: default
            if (stored.containsKey(key) && raw !is Boolean) {
                prefs.edit().putBoolean(key, value).apply()
            }
            value
        } catch (e: ClassCastException) {
            Log.w(tag, "Unreadable pref $key", e)
            prefs.edit().remove(key).apply()
            default
        }
    }

    suspend fun syncSystemInstalledExtensions(): Int = withContext(Dispatchers.IO) {
        val discovered = extensionLoader.discoverSystemExtensionPackages()
        val localSnapshot = extensionLoader.snapshotLocalApks()
        val localInfos = extensionLoader.scanLocalExtensions()
        val localPackages = localInfos.map { it.packageName }.toSet()
        val discoveredPackages = discovered.map { it.packageName }.toSet()
        val untrusted = mutableListOf<Extension.Untrusted>()

        var upserted = 0
        for (info in discovered) {
            if (!isPackageAllowed(info.packageName)) continue

            val signatureOk = try {
                val flags = ExtensionSignatureTrust.packageFlags()
                @Suppress("DEPRECATION")
                val pkgInfo = context.packageManager.getPackageInfo(info.packageName, flags)
                signatureTrust.isTrusted(pkgInfo, signatureTrust.getSignatures(pkgInfo))
            } catch (_: Exception) {
                info.signatureHash?.let(ExtensionSignatureTrust::isOfficialFingerprint) == true
            }

            val existing = installedDao.getById(info.packageName)
            val shouldTrust = signatureOk
            if (!shouldTrust && !info.signatureHash.isNullOrBlank()) {
                untrusted.add(
                    Extension.Untrusted(
                        id = info.packageName,
                        name = info.name,
                        version = info.versionName,
                        nsfw = info.nsfw,
                        pkgName = info.packageName,
                        versionCode = info.versionCode,
                        signatureHash = info.signatureHash!!,
                        extensionLibVersion = info.extensionLibVersion,
                        libCompatibility = info.libCompatibility,
                    ),
                )
            }

            installedDao.insert(
                InstalledExtensionEntity(
                    id = info.packageName,
                    name = info.name,
                    isTrusted = shouldTrust,
                    dateInstalled = existing?.dateInstalled ?: System.currentTimeMillis(),
                    versionCode = info.versionCode,
                ),
            )
            upserted++
        }

        _untrustedExtensions.value = untrusted

        for (info in localInfos) {
            if (info.packageName in discoveredPackages) continue
            if (!isPackageAllowed(info.packageName)) continue
            val existing = installedDao.getById(info.packageName)
            val trusted = existing?.isTrusted == true ||
                runCatching { extensionLoader.isApkTrustedForExecution(info) }.getOrDefault(false)
            installedDao.insert(
                InstalledExtensionEntity(
                    id = info.packageName,
                    name = info.name.ifBlank { existing?.name ?: info.packageName },
                    isTrusted = trusted,
                    dateInstalled = existing?.dateInstalled ?: System.currentTimeMillis(),
                    versionCode = info.versionCode.takeIf { it > 0L } ?: existing?.versionCode ?: 0L,
                ),
            )
            upserted++
        }

        // Drop a Room row only when the package is gone from PackageManager AND the
        // private APK file is gone. An unreadable APK keeps its row so the next launch
        // can try again instead of wiping the install.
        val prefix = ExtensionLoader.TACHIYOMI_EXTENSION_PREFIX
        val stillPresent = discoveredPackages + localPackages
        val staleIds = InstalledExtensionReconcile.rowIdsToRemove(
            rowIds = installedDao.getAll().map { it.id },
            stillPresent = stillPresent,
            apkOnDisk = localSnapshot.packageNames,
            directoryReadable = localSnapshot.directoryReadable,
            packagePrefix = prefix,
        )
        for (id in staleIds) {
            Log.i(tag, "Removing stale system extension from DB: $id")
            installedDao.delete(id)
        }

        refreshTrustCache()
        if (upserted > 0) {
            Log.i(tag, "Synced $upserted system extension package(s) into installed_extensions")
        }
        upserted
    }

    // ─── Load installed extensions (called on app startup) ───

    suspend fun loadInstalledExtensions(force: Boolean = false) = withContext(Dispatchers.IO) {
        // Avoid clobbering an in-flight repo refresh unless explicitly forced
        if (_isLoading.value && !force && !_isRepoRefreshing.value) return@withContext

        loadMutex.withLock {
            val ownsLoadingFlag = !_isRepoRefreshing.value
            if (ownsLoadingFlag) _isLoading.value = true
            try {
                // Re-discover PackageManager installs before purge/load so Installed Sources stays in sync
                syncSystemInstalledExtensions()
                purgeNonWhitelistedExtensionsInternal()
                ensureDefaultRepo()
                refreshTrustCache()

                Log.d(tag, "Scanning for extensions (system + local)...")
                val systemInfos = extensionLoader.scanInstalledExtensions()
                val localInfos = extensionLoader.scanLocalExtensions()

                // Merge: Local extensions take precedence
                val installedInfos = mergeInstalledExtensionInfos(localInfos, systemInfos)

                val trusted = _trustedPackages.value

                val advanced = isAdvancedMode()

                val allowedInfos = ExtensionAllowlist.dedupeBySlugPreferEn(
                    installedInfos.filter { isPackageAllowed(it.packageName) },
                    packageSelector = { it.packageName },
                    advancedMode = advanced,
                )
                _installedExtensionInfos.value = allowedInfos

                val currentFingerprint = ExtensionInstallRules.buildFingerprint(
                    allowedInfos
                        .filter { extensionLoader.isApkTrustedForExecution(it) }
                        .map(::fingerprintEntry),
                    advanced = advanced,
                    langs = _sourceLangs.value,
                )

                // Skip even when the previous load produced 0 sources (e.g. all failed).
                // Requiring isNotEmpty() caused a tight retry loop on Android 14+ writable-dex failures.
                if (!force && currentFingerprint == lastLoadedFingerprint) {
                    Log.d(tag, "No changes in trusted extensions. Skipping source reload.")
                    return@withLock
                }

                val loadedSources = mutableListOf<Source>()
                val preservedBuiltIns = builtInSourceIds.mapNotNull { sourceRegistry[it] }
                sourceRegistry.clear() // Clear registry before repopulating

                Log.d(tag, "Installed extensions: ${allowedInfos.size}. Trusted packages: ${trusted.size}")

                val perExtensionResults = allowedInfos
                    .filter { extInfo ->
                        val executable = extensionLoader.isApkTrustedForExecution(extInfo)
                        if (!executable) {
                            Log.w(tag, "Extension NOT executable (signature): ${extInfo.packageName}. Skipping source load.")
                        }
                        executable
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
                                        wrapTachiyomiSource(tSource, iconUri) to extInfo.packageName
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

                for (builtIn in preservedBuiltIns) {
                    loadedSources.add(builtIn)
                    sourceRegistry[builtIn.id] = builtIn
                }

                val activeSources = ExtensionAllowlist.dedupeSourcesBySlugPreferEn(
                    loadedSources,
                    advancedMode = advanced,
                )

                _installedExtensions.value = activeSources
                applyPinnedSources()

                Log.i(
                    tag,
                    "Finished loading extensions. Instantiated ${activeSources.size} English sources " +
                        "(from ${perExtensionResults.size} total)."
                )

                lastLoadedFingerprint = currentFingerprint
                refreshBaseUrlRewriteRegistry()
                Log.i(tag, "Total sources active: ${activeSources.size} (Registry size: ${sourceRegistry.size})")
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

    fun getConfigurableSource(sourceId: String): eu.kanade.tachiyomi.source.ConfigurableSource? {
        val adapter = sourceRegistry[sourceId] as? TachiyomiSourceAdapter ?: return null
        return adapter.tachiyomiSource as? eu.kanade.tachiyomi.source.ConfigurableSource
    }

    fun getSourceName(sourceId: String): String? = sourceRegistry[sourceId]?.name

    fun getDefaultBaseUrl(sourceId: String): String {
        val source = sourceRegistry[sourceId] ?: return ""
        return source.defaultBaseUrl
    }

    fun getBaseUrlOverride(sourceId: String): String? =
        _baseUrlOverrides.value[sourceId]?.trim()?.takeIf { it.isNotBlank() }

    fun effectiveBaseUrl(sourceId: String): String {
        val source = sourceRegistry[sourceId] ?: return ""
        return source.baseUrl
    }

    suspend fun setBaseUrlOverride(sourceId: String, url: String?) {
        settingsManager.setSourceBaseUrlOverride(sourceId, url)
    }

    private fun applyBaseUrlOverrides(overrides: Map<String, String>) {
        val seen = mutableSetOf<Source>()
        for (source in sourceRegistry.values) {
            if (!seen.add(source)) continue
            if (source is TachiyomiSourceAdapter) {
                source.setBaseUrlOverride(overrides[source.id])
            }
        }
        for (source in _installedExtensions.value) {
            if (!seen.add(source)) continue
            if (source is TachiyomiSourceAdapter) {
                source.setBaseUrlOverride(overrides[source.id])
            }
        }
        refreshBaseUrlRewriteRegistry(overrides)
        if (_installedExtensions.value.isNotEmpty()) {
            _installedExtensions.value = _installedExtensions.value.toList()
        }
    }

    private fun refreshBaseUrlRewriteRegistry(overrides: Map<String, String> = _baseUrlOverrides.value) {
        val sources = sourceRegistry.values.distinctBy { it.id }
        BaseUrlOverrideRegistry.update(BaseUrlResolver.buildHostRewriteMap(sources, overrides))
    }

    private fun wrapTachiyomiSource(tSource: TachiyomiSource, iconUri: String): TachiyomiSourceAdapter {
        val sourceId = tSource.id.toString()
        return TachiyomiSourceAdapter(
            tachiyomiSource = tSource,
            icon = iconUri,
            baseUrlOverride = getBaseUrlOverride(sourceId),
        )
    }

    fun getAllSources(): List<Source> {
        return ExtensionAllowlist.dedupeSourcesBySlugPreferEn(
            _installedExtensions.value.filter { it.id != com.radiant.webtoon.core.local.LocalSourceIds.LOCAL_SOURCE_ID },
            advancedMode = isAdvancedMode(),
        )
    }

    fun registerSource(source: Source) {
        val current = _installedExtensions.value.toMutableList()
        if (current.none { it.id == source.id }) {
            current.add(source)
            _installedExtensions.value = current
        }
        sourceRegistry[source.id] = source
        builtInSourceIds.add(source.id)
    }

    /**
     * Convert installed extension infos to Extension model for UI display.
     */
    fun getInstalledAsExtensions(): List<Extension> {
        val trusted = _trustedPackages.value
        val updatesMap = _availableUpdates.value.associateBy { it.id }
        val trackedPackages = _managedPackages.value

        return ExtensionAllowlist.dedupeBySlugPreferEn(
            _installedExtensionInfos.value.distinctBy { it.packageName }
                .filter { isPackageAllowed(it.packageName) }
                .filter { trackedPackages.contains(it.packageName) },
            packageSelector = { it.packageName },
            advancedMode = isAdvancedMode(),
        ).map { info ->
            val iconUri = if (info.apkPath.startsWith(context.filesDir.absolutePath)) {
                "apk:${info.apkPath}"
            } else {
                "pkg:${info.packageName}"
            }
            
            val updateMatch = updatesMap[info.packageName]
            val hasUpdate = updateMatch != null
            
            val downloadUrl = if (hasUpdate) {
                updateMatch?.apkUrl?.takeIf { it.isNotBlank() }
                    ?: updateMatch?.repoUrl?.takeIf { it.isNotBlank() }?.let { repo ->
                        buildExtensionApkUrl(repo, updateMatch.fileName)
                    }
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
                updateAvailable = updateMatch?.toUpdateAvailable(),
                downloadUrl = downloadUrl,
                extensionLibVersion = info.extensionLibVersion,
                libCompatibility = info.libCompatibility,
            )
        }
    }

    data class UpdateAllResult(
        val succeeded: Int,
        val failed: Int,
        val failures: List<Pair<String, String>>,
    )

    /**
     * Convert available repo entries to Extension model for UI display.
     * Only curated English sources are listed.
     */
    fun getAvailableAsExtensions(): List<Extension.Available> {
        val installedPkgs = _installedExtensionInfos.value.map { it.packageName }.toSet()
        val trusted = _trustedPackages.value
        val advanced = isAdvancedMode()
        val entries = ExtensionAllowlist.dedupeBySlugPreferEn(
            _availableExtensions.value.distinctBy { it.id },
            packageSelector = { it.id },
            advancedMode = advanced,
        )
            .filter { entry -> ExtensionAllowlist.isAllowedRepoEntry(entry) }
            .filter { entry -> isExtensionLanguageEnabled(entry.lang) }
            .filter { entry -> !installedPkgs.contains(entry.id) }
            // NSFW filter does not hide allowlisted packs (Manga18fx, Manhwa18.cc, etc.)
            .sortedBy { it.name.lowercase() }

        return entries.mapNotNull { entry ->
            val repoUrl = entry.installableRepoUrl() ?: return@mapNotNull null
            val effectiveBaseUrl = entry.baseUrl ?: entry.sources?.firstOrNull()?.baseUrl ?: ""
            val downloadUrl = entry.apkUrl?.takeIf { it.isNotBlank() }
                ?: buildExtensionApkUrl(repoUrl, entry.fileName)

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
                repoUrl = repoUrl,
                isInstalled = installedPkgs.contains(entry.id),
                isLanguageEnabled = isExtensionLanguageEnabled(entry.lang),
            )
        }
    }

    private fun RepoExtensionEntry.withRepoContext(repoUrl: String): RepoExtensionEntry {
        val iconUrl = icon?.takeIf { it.startsWith("http") }
            ?: "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/icon/$id.png"
        return copy(icon = iconUrl, repoUrl = repoUrl)
    }
}
