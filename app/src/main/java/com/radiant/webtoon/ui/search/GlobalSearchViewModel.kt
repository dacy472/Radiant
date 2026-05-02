package com.radiant.webtoon.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.model.SourceSearchResult
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.network.NetworkHelper
import com.radiant.webtoon.core.database.Manga

class GlobalSearchViewModel(private val repository: WebtoonRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _migrateFromManga = MutableStateFlow<Manga?>(null)
    val migrateFromManga: StateFlow<Manga?> = _migrateFromManga

    private val _searchResults = MutableStateFlow<Map<String, SourceSearchResult>>(emptyMap())
    
    private val _filterPinned = MutableStateFlow(false)
    val filterPinned: StateFlow<Boolean> = _filterPinned

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

    val activeSources = repository.extensionManager.installedExtensions
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val networkHelper: NetworkHelper by injectLazy()

    init {
        viewModelScope.launch {
            repository.extensionManager.installedExtensionInfos.collect { installed ->
                Log.d("GlobalSearchViewModel", "Installed extensions size: ${installed.size}")
                _installedCount.value = installed.size
            }
        }
        
        // Populate source count from active extensions
        viewModelScope.launch {
            repository.extensionManager.installedExtensions.collect { sources ->
                Log.d("GlobalSearchViewModel", "Active extensions updated: ${sources.size}")
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

    // Internal search job tracking removed per request

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        // We no longer trigger search automatically as the user types
        if (newQuery.isEmpty()) {
            _searchResults.value = emptyMap()
        }
    }

    fun toggleFilterPinned() {
        _filterPinned.value = !_filterPinned.value
        // If a search is already active/completed, re-trigger it with new filters
        if (_query.value.length >= 2) {
            search(_query.value)
        }
    }

    fun toggleFilterHasResults() {
        _filterHasResults.value = !_filterHasResults.value
    }

    fun search(query: String) {
        if (query.length < 2) return
        viewModelScope.launch {
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        Log.e("RADIANT_DEBUG", "Search started for query: $query")
        _isSearching.value = true
        _error.value = null

        try {
            // Get pre-loaded extensions from cache (INSTANT)
            val allSources = repository.extensionManager.installedExtensions.value
            val sources = if (_filterPinned.value) allSources.filter { it.isPinned } else allSources
            Log.e("RADIANT_DEBUG", "ExtensionManager currently holds ${sources.size} target active extensions")

            // If we have no sources, try one automatic reload
            if (sources.isEmpty() && !_filterPinned.value) {
                repository.extensionManager.refreshSources()
            }

            val initialResults = sources.associate {
                it.id to SourceSearchResult(
                    sourceId = it.id,
                    sourceName = it.name,
                    sourceIcon = it.icon,
                    sourceLang = it.lang,
                    webtoons = emptyList(),
                    error = null
                )
            }
            _searchResults.value = initialResults
            
            // Search only targeted sources
            repository.searchAllStreaming(query, sources).collect { result ->
                _searchResults.update { current ->
                    current + (result.sourceId to result)
                }
            }
            
            // Collect any global errors from the final map if needed
            val results = _searchResults.value.values.toList()
            val successfulResults = results.filter { it.webtoons.isNotEmpty() }
            val sourceErrors = results.filter { it.webtoons.isEmpty() && it.error != null }
            
            if (successfulResults.isEmpty()) {
                if (sourceErrors.isNotEmpty()) {
                    val errorSummary = sourceErrors.joinToString("\n") { "${it.sourceName}: ${it.error}" }
                    _error.value = "Search failed across all sources:\n$errorSummary"
                } else {
                    val activeSourcesCount = repository.extensionManager.getAllSources().size
                    _error.value = if (activeSourcesCount == 0) {
                        "No extensions installed. Go to Browse to add some."
                    } else {
                        "No results found."
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // SILENTLY IGNORE. The user just typed another letter. 
            // Do NOT push an error state to the UI.
            throw e 
        } catch (e: Exception) {
            Log.e("RADIANT_DEBUG", "Search crashed: ${e.message}", e)
            _error.value = "Search failed: ${e.localizedMessage}"
            _searchResults.value = emptyMap()
        } finally {
            _isSearching.value = false
        }
    }

    fun refreshSources() {
        viewModelScope.launch {
            repository.extensionManager.refreshSources()
            if (_query.value.length >= 2) {
                performSearch(_query.value)
            }
        }
    }
}
