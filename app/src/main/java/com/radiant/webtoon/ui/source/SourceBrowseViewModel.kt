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
    private var source: Source? = null
    private var currentQuery: String = ""
    private var activeFilters: FilterList = FilterList()

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

        viewModelScope.launch {
            if (currentPage == 1) {
                _state.value = BrowseState.Loading
            }

            try {
                when (_selectedTab.value) {
                    BrowseTab.POPULAR -> {
                        val response = s.fetchPopularManga(currentPage)
                        _webtoons.value = _webtoons.value + response.webtoons
                        hasNextPage = response.hasNextPage
                    }
                    BrowseTab.LATEST -> {
                        val response = s.fetchLatestManga(currentPage)
                        _webtoons.value = _webtoons.value + response.webtoons
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
                            _webtoons.value = _webtoons.value + newWebtoons
                            hasNextPage = response.hasNextPage
                        }
                    }
                }
                currentPage++
                _state.value = BrowseState.Success
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep previously loaded pages; only show error when the first page fails
                if (_webtoons.value.isEmpty()) {
                    _state.value = BrowseState.Error(e.message ?: "Unknown error")
                } else {
                    _state.value = BrowseState.Success
                }
            }
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
        _webtoons.value = emptyList()
        loadNextPage()
    }

    fun getSourceName(): String = source?.name ?: "Browse"
}

sealed class BrowseState {
    object Loading : BrowseState()
    object Success : BrowseState()
    data class Error(val message: String) : BrowseState()
}
