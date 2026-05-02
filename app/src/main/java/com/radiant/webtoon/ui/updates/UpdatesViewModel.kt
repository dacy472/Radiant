package com.radiant.webtoon.ui.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.radiant.webtoon.core.database.UpdateItem
import com.radiant.webtoon.core.repository.WebtoonRepository
import com.radiant.webtoon.core.service.UpdateWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpdatesViewModel(
    application: Application,
    private val repository: WebtoonRepository
) : AndroidViewModel(application) {

    private val _updates = MutableStateFlow<List<UpdateItem>>(emptyList())
    val updates: StateFlow<List<UpdateItem>> = _updates

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        loadUpdates()
    }

    private fun loadUpdates() {
        viewModelScope.launch {
            repository.getRecentUpdates().collect {
                _updates.value = it
            }
        }
    }

    fun refreshManually() {
        _isRefreshing.value = true
        val request = OneTimeWorkRequestBuilder<com.radiant.webtoon.core.service.LibraryUpdateWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(request)
        
        // Wait briefly for UI to show feedback
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            _isRefreshing.value = false
        }
    }
}

class UpdatesViewModelFactory(
    private val application: Application,
    private val repository: WebtoonRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UpdatesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UpdatesViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
