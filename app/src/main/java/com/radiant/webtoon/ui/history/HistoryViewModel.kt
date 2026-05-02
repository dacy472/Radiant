package com.radiant.webtoon.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.radiant.webtoon.core.repository.WebtoonRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: WebtoonRepository
) : ViewModel() {

    val historyState: StateFlow<List<HistoryItem>> = repository.getHistoryFlow()
        .map { items ->
            items.map { item ->
                HistoryItem(
                    id = item.chapter_id,
                    webtoonTitle = item.webtoon_title,
                    chapterTitle = item.chapter_title,
                    webtoonThumbnail = item.webtoon_thumbnail,
                    lastRead = item.read_at,
                    sourceId = item.source_id,
                    webtoonId = item.webtoon_id,
                    chapterId = item.chapter_id
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.clearHistory()
        }
    }
}

class HistoryViewModelFactory(
    private val repository: WebtoonRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
