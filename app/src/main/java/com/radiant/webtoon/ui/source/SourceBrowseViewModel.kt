package com.radiant.webtoon.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.model.Webtoon
import com.radiant.webtoon.core.source.ExtensionManager
import com.radiant.webtoon.core.source.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SourceBrowseViewModel(
    private val extensionManager: ExtensionManager,
    private val sourceId: String
) : ViewModel() {

    private val _state = MutableStateFlow<BrowseState>(BrowseState.Loading)
    val state: StateFlow<BrowseState> = _state

    private val _webtoons = MutableStateFlow<List<Webtoon>>(emptyList())
    val webtoons: StateFlow<List<Webtoon>> = _webtoons

    private var currentPage = 1
    private var hasNextPage = true
    private var source: Source? = null
    private var currentQuery: String? = null

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

    fun loadNextPage() {
        val s = source ?: return
        if (!hasNextPage && currentPage > 1) return

        viewModelScope.launch {
            if (currentPage == 1) {
                _state.value = BrowseState.Loading
            }
            
            try {
                if (currentQuery.isNullOrBlank()) {
                    val response = s.fetchPopularManga(currentPage)
                    _webtoons.value = _webtoons.value + response.webtoons
                    hasNextPage = response.hasNextPage
                } else {
                    val response = s.fetchSearchManga(page = currentPage, query = currentQuery!!, filters = eu.kanade.tachiyomi.source.model.FilterList())
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
                currentPage++
                _state.value = BrowseState.Success
            } catch (e: Exception) {
                _state.value = BrowseState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun searchSource(query: String) {
        currentQuery = query
        currentPage = 1
        hasNextPage = true
        _webtoons.value = emptyList()
        loadNextPage()
    }

    fun refresh() {
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
