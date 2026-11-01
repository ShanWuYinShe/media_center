package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ApiException
import com.example.mediacenter.data.BilibiliApiException
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.parsePageId
import com.example.mediacenter.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playbackResult: PlaybackResult? = null,
    // 服务端下发的合集队列(仅 approved 剧集);无合集/加载失败为 null。
    val collection: PlaybackCollection? = null,
    // 片尾自动连播的下一步(多P下一页优先,其次合集下一集);null=不连播。
    val next: PlaybackNext? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val videoId: String,
    private val repository: MediaRepositoryApi = MediaRepositoryHolder.repository,
    // 初始分页(0 起始):继续观看按页直达时由路由传入;>0 时优先于 id 解析
    private val initialPage: Int = 0,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // 合集下一集需要由 UI 导航(替换当前播放页);用一次性事件而不是状态,
    // 避免重组重复导航。多P下一页在 ViewModel 内直接 resolve。
    private val _episodeEvents = MutableSharedFlow<VideoSummary>(extraBufferCapacity = 1)
    val episodeEvents: SharedFlow<VideoSummary> = _episodeEvents.asSharedFlow()

    // 当前清晰度与分页(多P),切换分页时保持已选清晰度。
    private var currentQn: Int? = null
    private var currentPage: Int = maxOf(initialPage, parsePageId(videoId).second)
    // 解析任务:快速连续切换清晰度/分P时取消旧的,避免旧响应覆盖新选择
    private var resolveJob: Job? = null

    init {
        resolvePlayback()
        loadCollection()
    }

    // 合集队列与播放解析并行加载:合集失败绝不能影响视频播放/解析。
    private fun loadCollection() {
        viewModelScope.launch {
            try {
                // catalog 的多P条可能是 bvid_pN,合集关系按 base bvid 存。
                val lookupID = if (repository.currentProvider == "bilibili") {
                    parsePageId(videoId).first
                } else {
                    videoId
                }
                val collection = playbackCollectionFrom(repository.collectionFor(lookupID))
                if (collection != null) {
                    _uiState.value = _uiState.value.copy(collection = collection)
                    updateNext()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Player", "load collection failed: ${e.message}", e)
            }
        }
    }

    fun resolvePlayback() = launch { resolve(currentQn, currentPage) }

    /**
     * 切换清晰度:目标档已在解析结果的 options 中(YouTube 由 server 一次返回
     * 全部档位)时本地换源——把选中档提前到 options 首位,PlayerContent 检测到
     * url 变化即重载,免去再次解析(YouTube 解析是服务端秒级操作且有频控)。
     * 未命中(如 bilibili,每次解析只含当前档一条流)仍按新 qn 重新解析。
     */
    fun selectQuality(qn: Int) {
        // 重复点当前档位不重新解析(YouTube 解析虽被服务端缓存,仍是一次网络请求)
        if (qn == currentQn) return
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
            // 记录服务端实际给出的清晰度(可能因会员/片源自动降级),UI 选中态与
            // 后续"重复点当前档位"判断都以它为准。
            currentQn = result.options.firstOrNull()?.qn ?: qn
            _uiState.value = _uiState.value.copy(playbackResult = result, isLoading = false)
            updateNext()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Player", "resolvePlayback failed: ${e.message}", e)
            // B 站/服务端异常的 message 已是面向用户的中文具体原因(风控/无分P/
            // 令牌失效等),透出比固定文案可诊断——TV 上用户报错只有这一行字,
            // "解析失败,请重试"无法区分是网络问题还是内容问题;其余异常
            // (原始信息是英文技术细节)仍用通用友好文案。
            val friendly = when (e) {
                is BilibiliApiException, is ApiException -> e.message
                else -> "播放地址解析失败，请重试"
            }
            _uiState.value = _uiState.value.copy(isLoading = false, error = friendly)
        }
    }

    /**
     * 片尾到达:有下一步则推进(多P下一页直接换页,合集下一集发导航事件)。
     * 返回是否真的还有下一步;UI 只负责把 ENDED 边沿传过来。
     */
    fun onPlaybackEnded(): Boolean {
        val next = _uiState.value.next ?: return false
        when (next) {
            is PlaybackNext.Page -> selectPage(next.index)
            is PlaybackNext.Episode -> _episodeEvents.tryEmit(next.video)
        }
        return true
    }

    /** 遥控器 ↓：手动切下一集（多P下一页或合集下一集）。 */
    fun playNextManual(): Boolean = onPlaybackEnded()

    /** 遥控器 ↑：手动切上一集（多P上一页或合集上一集）。 */
    fun playPreviousManual(): Boolean {
        val state = _uiState.value
        val pages = state.playbackResult?.pages
        if (pages != null && pages.currentPage > 0) {
            selectPage(pages.currentPage - 1)
            return true
        }
        val collection = state.collection ?: return false
        if (collection.currentIndex > 0) {
            _episodeEvents.tryEmit(collection.items[collection.currentIndex - 1])
            return true
        }
        return false
    }

    private fun updateNext() {
        val state = _uiState.value
        _uiState.value = state.copy(next = nextPlayback(state.playbackResult, state.collection))
    }
}

class PlayerViewModelFactory(
    private val videoId: String,
    private val initialPage: Int = 0,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(videoId, initialPage = initialPage) as T
    }
}
