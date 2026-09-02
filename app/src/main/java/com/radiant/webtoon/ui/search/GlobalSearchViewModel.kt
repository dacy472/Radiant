package com.radiant.webtoon.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.model.SourceSearchResult
import com.radiant.webtoon.core.model.SourceSearchStatus
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.radiant.webtoon.core.database.Manga

class GlobalSearchViewModel(private val repository: WebtoonRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _migrateFromManga = MutableStateFlow<Manga?>(null)
    val migrateFromManga: StateFlow<Manga?> = _migrateFromManga

    private val _searchResults = MutableStateFlow<Map<String, SourceSearchResult>>(emptyMap())

    private val _filterHasResults = MutableStateFlow(false)
    val filterHasResults: StateFlow<Boolean> = _filterHasResults

    val searchResults: StateFlow<List<SourceSearchResult>> =
        kotlinx.coroutines.flow.combine(_searchResults, _filterHasResults) { resultsMap, hideEmpty ->
            resultsMap.values
                .filter { if (hideEmpty) it.webtoons.isNotEmpty() else true }
                .sortedByDescending { res -> res.webtoons.size }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    /** Installed sources shown in search; migration mode searches all other installed sources. */
    val searchableSources = kotlinx.coroutines.flow.combine(
        repository.extensionManager.installedExtensions,
        _migrateFromManga,
    ) { _, migrateFrom ->
        val allInstalled = repository.extensionManager.getAllSources()
        if (migrateFrom != null) {
            allInstalled.filter { it.id != migrateFrom.source }
        } else {
            allInstalled.filter { it.isPinned }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch {
            repository.extensionManager.installedExtensionInfos.collect { installed ->
                Log.d("GlobalSearchViewModel", "Installed extensions size: ${installed.size}")
                _installedCount.value = installed.size
            }
        }
    }

    fun setInitialQuery(initialQuery: String, migrateFrom: Manga? = null) {
        if (_query.value.isEmpty() && initialQuery.isNotEmpty()) {
            _query.value = initialQuery
            _migrateFromManga.value = migrateFrom
            search(initialQuery)
        }
    }

    private var searchJob: Job? = null

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isEmpty()) {
            searchJob?.cancel()
            _searchResults.value = emptyMap()
        }
    }

    fun toggleFilterHasResults() {
        _filterHasResults.value = !_filterHasResults.value
    }

    fun search(query: String) {
        if (query.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(query)
        }
    }

    fun loadMoreForSource(sourceId: String) {
        val query = _query.value
        if (query.length < 2) return
        val current = _searchResults.value[sourceId] ?: return
        if (!current.hasNextPage) return
        val nextPage = current.currentPage + 1
        viewModelScope.launch {
            val more = repository.searchSourceNextPage(sourceId, query, nextPage) ?: return@launch
            _searchResults.update { map ->
                val existing = map[sourceId] ?: return@update map
                val seen = existing.webtoons.map { it.url?.takeIf { url -> url.isNotBlank() } ?: it.id }.toMutableSet()
                val unique = more.webtoons.filter { item ->
                    seen.add(item.url?.takeIf { url -> url.isNotBlank() } ?: item.id)
                }
                map + (sourceId to existing.copy(
                    webtoons = existing.webtoons + unique,
                    hasNextPage = more.hasNextPage,
                    currentPage = nextPage,
                    error = more.error,
                    status = more.status,
                ))
            }
        }
    }

    private suspend fun performSearch(query: String) {
        Log.d("GlobalSearchViewModel", "Search started for query: $query")
        _isSearching.value = true
        _error.value = null

        try {
            val migrateFrom = _migrateFromManga.value
            var sources = if (migrateFrom != null) {
                repository.extensionManager.getAllSources().filter { it.id != migrateFrom.source }
            } else {
                repository.extensionManager.getAllSources().filter { it.isPinned }
            }
            Log.d("GlobalSearchViewModel", "Search sources before reload: ${sources.size}")

            if (sources.isEmpty()) {
                repository.extensionManager.refreshSources()
                sources = if (migrateFrom != null) {
                    repository.extensionManager.getAllSources().filter { it.id != migrateFrom.source }
                } else {
                    repository.extensionManager.getAllSources().filter { it.isPinned }
                }
                Log.d("GlobalSearchViewModel", "Search sources after reload: ${sources.size}")
            }

            if (sources.isEmpty()) {
                _searchResults.value = emptyMap()
                val installedCount = repository.extensionManager.getAllSources().size
                _error.value = if (installedCount == 0) {
                    "No extensions installed. Go to Browse to add some."
                } else if (migrateFrom != null) {
                    "No other installed sources to migrate to."
                } else {
                    "No pinned sources. Pin extensions in Browse to search them."
                }
                return
            }

            val initialResults = sources.associate {
                it.id to SourceSearchResult(
                    sourceId = it.id,
                    sourceName = it.name,
                    sourceIcon = it.icon,
                    sourceLang = it.lang,
                    webtoons = emptyList(),
                    error = null,
                    status = SourceSearchStatus.Loading,
                )
            }
            _searchResults.value = initialResults

            repository.searchAllStreaming(query, sources).collect { result ->
                _searchResults.update { current ->
                    current + (result.sourceId to result)
                }
            }

            val results = _searchResults.value.values.toList()
            val successfulResults = results.filter { it.webtoons.isNotEmpty() }
            val sourceErrors = results.filter { it.status == SourceSearchStatus.Error }
            val verificationNeeded = results.filter { it.status == SourceSearchStatus.VerificationNeeded }

            if (successfulResults.isEmpty()) {
                when {
                    verificationNeeded.isNotEmpty() && sourceErrors.isEmpty() -> {
                        _error.value = null
                    }
                    sourceErrors.isNotEmpty() -> {
                        val errorSummary = sourceErrors.joinToString("\n") { "${it.sourceName}: ${it.error}" }
                        _error.value = "Search failed across all sources:\n$errorSummary"
                    }
                    else -> {
                        _error.value = "No results found."
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GlobalSearchViewModel", "Search crashed: ${e.message}", e)
            _error.value = "Search failed: ${e.localizedMessage}"
            _searchResults.value = emptyMap()
        } finally {
            _isSearching.value = false
        }
    }

    fun refreshSources() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            repository.extensionManager.refreshSources()
            if (_query.value.length >= 2) {
                performSearch(_query.value)
            }
        }
    }
}
