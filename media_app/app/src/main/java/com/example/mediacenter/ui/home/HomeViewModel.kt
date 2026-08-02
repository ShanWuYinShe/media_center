package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val categories: List<ContentCategory> = emptyList(),
    val categoryVideos: Map<String, List<VideoSummary>> = emptyMap(),
    val providers: List<ProviderInfo> = emptyList(),
    val currentProvider: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 跟踪当前加载任务:切换 provider / 刷新时取消旧任务,
    // 避免慢的旧请求后完成覆盖新状态(旧 provider 内容配新 currentProvider)。
    private var loadJob: Job? = null

    init { loadHome() }

    fun loadHome() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val providers = repository.providers()
                _uiState.value = _uiState.value.copy(
                    providers = providers,
                    currentProvider = repository.currentProvider,
                )
                val cats = repository.categories()
                // 并行拉取各分类第一页,避免分类多时首页加载时间线性增长
                val videos = coroutineScope {
                    cats.map { cat ->
                        async { cat.id to repository.catalogPage(cat.id).items.orEmpty() }
                    }.awaitAll().toMap()
                }
                _uiState.value = _uiState.value.copy(categories = cats, categoryVideos = videos, isLoading = false)
            } catch (e: Exception) {
                // 被取消的任务(CancellationException)不当作错误展示
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /** 切换视频源后重新加载首页内容。 */
    fun selectProvider(provider: String) {
        repository.setProvider(provider)
        loadHome()
    }
}
