package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.parsePageId
import com.example.mediacenter.log.AppLog
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

    /**
     * 切换清晰度:目标档已在解析结果的 options 中(YouTube 由 server 一次返回
     * 全部档位)时本地换源——把选中档提前到 options 首位,PlayerContent 检测到
     * url 变化即重载,免去再次解析(YouTube 解析是服务端秒级操作且有频控)。
     * 未命中(如 bilibili,每次解析只含当前档一条流)仍按新 qn 重新解析。
     */
    fun selectQuality(qn: Int) {
        val current = _uiState.value.playbackResult
        val active = current?.options?.firstOrNull()
        val match = current?.options?.firstOrNull { it.qn == qn }
        if (current != null && active != null && match != null && match.url != active.url) {
            currentQn = qn
            _uiState.value = _uiState.value.copy(
                playbackResult = current.copy(
                    options = listOf(match) + current.options.filter { it.url != match.url },
                ),
            )
            return
        }
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
            // "_pN" 分P后缀是 bilibili catalog 的约定;限定 bilibili 才解析/拼接,
            // YouTube 视频 ID 字符集虽含 "_",但其 11 字符 ID 必须原样透传。
            val targetId = if (repository.currentProvider == "bilibili") {
                val (bvid, _) = parsePageId(videoId)
                if (pageIndex > 0) "${bvid}_p${pageIndex + 1}" else bvid
            } else {
                videoId
            }
            val result = repository.resolvePlayback(targetId, qn)
            _uiState.value = _uiState.value.copy(playbackResult = result, isLoading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 原始异常可能是英文技术信息;记日志、给固定友好文案(与分类页一致)
            AppLog.e("Player", "resolvePlayback failed: ${e.message}", e)
            _uiState.value = _uiState.value.copy(isLoading = false, error = "播放地址解析失败，请重试")
        }
    }
}

class PlayerViewModelFactory(private val videoId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(videoId) as T
    }
}
