package com.radiant.webtoon.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class BrowseTab { POPULAR, LATEST, SEARCH }

class SourceBrowseViewModel(
    private val extensionManager: ExtensionManager,
    private val sourceId: String
) : ViewModel() {

    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state

    private val _webtoons = MutableStateFlow<List<Webtoon>>(emptyList())
    val webtoons: StateFlow<List<Webtoon>> = _webtoons

    private val _selectedTab = MutableStateFlow(BrowseTab.POPULAR)
    val selectedTab: StateFlow<BrowseTab> = _selectedTab

    private var currentPage = 1
    private var hasNextPage = true
    private var isLoadingPage = false
    private var source: Source? = null
    private var currentQuery: String = ""
    private var activeFilters: FilterList = FilterList()
    private var repairAttempted = false

    val supportsLatest: Boolean
        get() = source?.capabilities?.supportsLatest == true

    fun currentFilters(): FilterList = source?.getFilterList() ?: FilterList()

    fun applyFilters(filters: FilterList) {
        activeFilters = filters
        if (_selectedTab.value == BrowseTab.SEARCH && currentQuery.isNotBlank()) {
            resetAndLoad()
        }
    }

    init {
        loadSource()
    }

    private fun loadSource() {
        source = extensionManager.getSource(sourceId)
        if (source == null) {
            _state.value = BrowseState.Error("Source not found")
        } else {
            loadNextPage()
        }
    }

    fun selectTab(tab: BrowseTab) {
        if (_selectedTab.value == tab && tab != BrowseTab.SEARCH) return
        _selectedTab.value = tab
        if (tab != BrowseTab.SEARCH) {
            currentQuery = ""
            resetAndLoad()
        }
    }

    fun loadNextPage() {
        val s = source ?: return
        if (!hasNextPage && currentPage > 1) return
        if (isLoadingPage) return

        viewModelScope.launch {
            isLoadingPage = true
            if (currentPage == 1) {
                _state.value = BrowseState.Loading
            }

            var skipLoadingReset = false
            try {
                when (_selectedTab.value) {
                    BrowseTab.POPULAR -> {
                        val response = s.fetchPopularManga(currentPage)
                        appendUniqueWebtoons(response.webtoons)
                        hasNextPage = response.hasNextPage
                    }
                    BrowseTab.LATEST -> {
                        val response = s.fetchLatestManga(currentPage)
                        appendUniqueWebtoons(response.webtoons)
                        hasNextPage = response.hasNextPage
                    }
                    BrowseTab.SEARCH -> {
                        if (currentQuery.isBlank()) {
                            _webtoons.value = emptyList()
                            hasNextPage = false
                        } else {
                            val response = s.fetchSearchManga(
                                page = currentPage,
                                query = currentQuery,
                                filters = activeFilters
                            )
                            val newWebtoons = response.mangas.map { smanga ->
                                Webtoon(
                                    id = smanga.url,
                                    url = smanga.url,
                                    title = smanga.title,
                                    thumbnail = smanga.thumbnail_url ?: ""
                                )
                            }
                            appendUniqueWebtoons(newWebtoons)
                            hasNextPage = response.hasNextPage
                        }
                    }
                }
                currentPage++
                _state.value = BrowseState.Success
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!repairAttempted && isSerializationFailure(e)) {
                    repairAttempted = true
                    val repaired = extensionManager.repairSourceAfterSerializationFailure(sourceId)
                    if (repaired) {
                        skipLoadingReset = true
                        source = extensionManager.getSource(sourceId)
                        currentPage = 1
                        _webtoons.value = emptyList()
                        hasNextPage = true
                        isLoadingPage = false
                        loadNextPage()
                        return@launch
                    }
                }
                val cf = eu.kanade.tachiyomi.network.CloudflareException.unwrap(e)
                val errorState = if (cf != null) {
                    BrowseState.Error(
                        message = "Verification needed",
                        isCloudflareChallenge = true,
                        challengeUrl = cf.url ?: getSourceBaseUrl().takeIf { it.isNotBlank() },
                    )
                } else {
                    BrowseState.Error(e.message ?: "Failed to load")
                }
                // Keep previously loaded pages; only show error when the first page fails
                if (_webtoons.value.isEmpty()) {
                    _state.value = errorState
                } else {
                    _state.value = BrowseState.Success
                }
            } finally {
                if (!skipLoadingReset) {
                    isLoadingPage = false
                }
            }
        }
    }

    private fun appendUniqueWebtoons(incoming: List<Webtoon>) {
        val existing = _webtoons.value
        val seen = existing.map { it.identityKey() }.toMutableSet()
        val unique = incoming.filter { item -> seen.add(item.identityKey()) }
        if (unique.isNotEmpty()) {
            _webtoons.value = existing + unique
        }
    }

    fun searchSource(query: String) {
        currentQuery = query
        _selectedTab.value = BrowseTab.SEARCH
        resetAndLoad()
    }

    fun refresh() {
        resetAndLoad()
    }

    private fun resetAndLoad() {
        currentPage = 1
        hasNextPage = true
        isLoadingPage = false
        _webtoons.value = emptyList()
        loadNextPage()
    }

    fun getSourceName(): String = source?.name ?: "Browse"

    fun getSourceBaseUrl(): String = source?.baseUrl?.trim().orEmpty().ifBlank {
        extensionManager.getSource(sourceId)?.baseUrl?.trim().orEmpty()
    }

    private fun isSerializationFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is kotlinx.serialization.MissingFieldException) return true
            if (current.javaClass.name.contains("MissingFieldException")) return true
            current = current.cause
        }
        return false
    }
}

internal fun Webtoon.identityKey(): String =
    url?.takeIf { it.isNotBlank() } ?: id

sealed class BrowseState {
    object Loading : BrowseState()
    object Success : BrowseState()
    data class Error(
        val message: String,
        val isCloudflareChallenge: Boolean = false,
        val challengeUrl: String? = null,
    ) : BrowseState()
}
