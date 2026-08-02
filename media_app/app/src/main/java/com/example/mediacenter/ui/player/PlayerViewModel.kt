package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.parsePageId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playbackResult: PlaybackResult? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val videoId: String,
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // 当前清晰度与分页(多P),切换分页时保持已选清晰度。
    private var currentQn: Int? = null
    private var currentPage: Int = parsePageId(videoId).second
    // 解析任务:快速连续切换清晰度/分P时取消旧的,避免旧响应覆盖新选择
    private var resolveJob: Job? = null

    init { resolvePlayback() }

    fun resolvePlayback() = launch { resolve(currentQn, currentPage) }

    /** 切换清晰度:按新 qn 重新解析真实播放流。 */
    fun selectQuality(qn: Int) {
        currentQn = qn
        launch { resolve(qn, currentPage) }
    }

    /** 切换多P分页(0 起始):重新解析该页播放流。 */
    fun selectPage(pageIndex: Int) {
        currentPage = pageIndex
        launch { resolve(currentQn, pageIndex) }
    }

    private fun launch(block: suspend () -> Unit) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch { block() }
    }

    private suspend fun resolve(qn: Int?, pageIndex: Int) {
        // 保留旧播放结果:清晰度/分P切换期间播放器不中断,新流就绪后无缝切换
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            // catalog id 可能本身带 "_pN" 后缀,重新拼出目标分页的 id
            val (bvid, _) = parsePageId(videoId)
            val targetId = if (pageIndex > 0) "${bvid}_p${pageIndex + 1}" else bvid
            val result = repository.resolvePlayback(targetId, qn)
            _uiState.value = _uiState.value.copy(playbackResult = result, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }
}

class PlayerViewModelFactory(private val videoId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(videoId) as T
    }
}
