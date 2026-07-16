package com.example.mediacenter.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<VideoSummary> = emptyList(),
    val categories: List<ContentCategory> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(
    private val repository: MediaRepository = MediaRepository(
        api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
    ),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    init { loadCategories() }

    private fun loadCategories() {
        viewModelScope.launch {
            try {
                repository.refreshCredentials()
                val cats = repository.categories()
                _uiState.value = _uiState.value.copy(categories = cats)
            } catch (_: Exception) { }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.length < 2) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            try {
                val cats = _uiState.value.categories
                val catId = cats.firstOrNull()?.id
                if (catId == null) {
                    _uiState.value = _uiState.value.copy(isSearching = false, results = emptyList())
                    return@launch
                }
                val response = repository.search(query, catId)
                _uiState.value = _uiState.value.copy(isSearching = false, results = response.items)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearching = false, error = e.message)
            }
        }
    }
}
