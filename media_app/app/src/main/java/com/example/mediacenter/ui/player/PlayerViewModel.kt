package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ApiException
import com.example.mediacenter.data.BilibiliApiException
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackPrefetcher
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.PlaybackTuning
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.parsePageId
import com.example.mediacenter.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    // 当前集(合集连播时同播放页内切换,不重建播放页/播放服务)。
    val videoId: String = "",
    val title: String = "",
    val posterUrl: String? = null,
    val durationSeconds: Long? = null,
    val playbackResult: PlaybackResult? = null,
    // 服务端下发的合集队列(仅 approved 剧集);无合集/加载失败为 null。
    val collection: PlaybackCollection? = null,
    // 片尾自动连播的下一步(多P下一页优先,其次合集下一集);null=不连播。
    val next: PlaybackNext? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val initialVideoId: String,
    private val repository: MediaRepositoryApi = MediaRepositoryHolder.repository,
    // 初始分页(0 起始):继续观看按页直达时由路由传入;>0 时优先于 id 解析
    private val initialPage: Int = 0,
    initialTitle: String = "",
    initialPosterUrl: String? = null,
    // 自动选档的清晰度上限(像素高度):设备档位与屏幕物理高度取小(见 PlayerScreen)。
    // Int.MAX_VALUE 表示不收敛(单元测试/未知屏幕)。
    private val maxInitialQualityQn: Int = Int.MAX_VALUE,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PlayerUiState(
            videoId = initialVideoId,
            title = initialTitle,
            posterUrl = initialPosterUrl,
        ),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // 当前集/清晰度/分页(多P):切换分页时保持已选清晰度。
    private var currentVideoId: String = initialVideoId
    private var currentQn: Int? = null
    private var currentPage: Int = maxOf(initialPage, parsePageId(initialVideoId).second)
    // 已解析结果对应的视频 id:合集切集时 collection 可能先于解析返回,避免用旧
    // 结果的 pages 与新的 collection 拼出错误的"下一集"。
    private var resolvedVideoId: String? = null
    // 解析/合集任务:快速连续切集/切清晰度时取消旧的,避免旧响应覆盖新选择
    private var resolveJob: Job? = null
    private var collectionJob: Job? = null
    // 阶段 0 基线:从 ViewModel 创建到首次解析成功/失败,以及每次解析耗时。
    private val createdAtMs = System.currentTimeMillis()

    init {
        resolvePlayback()
        loadCollection()
    }

    // 合集队列与播放解析并行加载:合集失败绝不能影响视频播放/解析。
    private fun loadCollection() {
        collectionJob?.cancel()
        val requestedVideoId = currentVideoId
        collectionJob = viewModelScope.launch {
            try {
                // catalog 的多P条可能是 bvid_pN,合集关系按 base bvid 存。
                val lookupID = if (repository.currentProvider == "bilibili") {
                    parsePageId(requestedVideoId).first
                } else {
                    requestedVideoId
                }
                val collection = playbackCollectionFrom(repository.collectionFor(lookupID))
                if (requestedVideoId != currentVideoId) return@launch
                _uiState.value = _uiState.value.copy(collection = collection)
                updateNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Player", "load collection failed: ${e.message}", e)
            }
        }
    }

    /**
     * 重新解析当前播放地址。
     *
     * @param forceRefresh 播放失败后的重试必须为 true:跳过 5 分钟预取缓存并让
     *   该视频的预取失效,否则会反复拿到同一批已过期/被拒的直链,重试等于没重试。
     */
    fun resolvePlayback(forceRefresh: Boolean = false) =
        launch { resolve(currentQn, currentPage, forceRefresh) }

    /**
     * 切换清晰度:目标档已在解析结果的 options 中(YouTube 由 server 一次返回
     * 全部档位)时本地换源——把选中档提前到 options 首位,PlayerContent 检测到
     * url 变化即重载,免去再次解析(YouTube 解析是服务端秒级操作且有频控)。
     * 未命中(如 bilibili,每次解析只含当前档一条流)仍按新 qn 重新解析。
     */
    fun selectQuality(qn: Int, forceRefresh: Boolean = false) {
        // 重复点当前档位不重新解析(YouTube 解析虽被服务端缓存,仍是一次网络请求);
        // 但播放失败重试/降档要强制刷新,不能因 qn 相同跳过。
        if (!forceRefresh && qn == currentQn) return
        val current = _uiState.value.playbackResult
        val active = current?.options?.firstOrNull()
        val match = current?.options?.firstOrNull { it.qn == qn }
        if (!forceRefresh && current != null && active != null && match != null && match.url != active.url) {
            currentQn = qn
            _uiState.value = _uiState.value.copy(
                playbackResult = current.copy(
                    options = listOf(match) + current.options.filter { it.url != match.url },
                ),
            )
            return
        }
        currentQn = qn
        launch { resolve(qn, currentPage, forceRefresh) }
    }

    /** 切换多P分页(0 起始):重新解析该页播放流。 */
    fun selectPage(pageIndex: Int) {
        currentPage = pageIndex
        launch { resolve(currentQn, pageIndex) }
    }

    /**
     * 合集连播:在同播放页内切换下一集——播放服务/MediaController/焦点都不重建,
     * 只换播放流;解析与合集队列并行刷新。旧集最后一帧保留到新流就绪,避免黑屏闪烁。
     */
    private fun switchEpisode(video: VideoSummary) {
        currentVideoId = video.id
        currentPage = 0
        currentQn = null
        resolvedVideoId = null
        _uiState.value = _uiState.value.copy(
            videoId = video.id,
            title = video.title,
            posterUrl = video.thumbnailUrlLarge ?: video.thumbnailUrl,
            durationSeconds = video.durationSeconds,
            next = null,
            isLoading = true,
            error = null,
        )
        resolvePlayback()
        loadCollection()
    }

    private fun launch(block: suspend () -> Unit) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch { block() }
    }

    private suspend fun resolve(qn: Int?, pageIndex: Int, forceRefresh: Boolean = false) {
        // 保留旧播放结果:清晰度/分P切换期间播放器不中断,新流就绪后无缝切换
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val requestedVideoId = currentVideoId
        val startedAtMs = System.currentTimeMillis()
        try {
            // "_pN" 分P后缀是 bilibili catalog 的约定;限定 bilibili 才解析/拼接,
            // YouTube 视频 ID 字符集虽含 "_",但其 11 字符 ID 必须原样透传。
            val targetId = if (repository.currentProvider == "bilibili") {
                val (bvid, _) = parsePageId(requestedVideoId)
                if (pageIndex > 0) "${bvid}_p${pageIndex + 1}" else bvid
            } else {
                requestedVideoId
            }
            // 详情页/下一集预取命中或在途时直接用,省掉解析等待;未命中正常解析并回填。
            // 播放失败重试(forceRefresh)必须绕过预取:缓存里是同一批失效直链。
            val prefetched = if (forceRefresh) {
                PlaybackPrefetcher.invalidate(repository, targetId)
                null
            } else {
                PlaybackPrefetcher.resolve(repository, targetId, qn)
            }
            val result = prefetched ?: repository.resolvePlayback(targetId, qn).also {
                PlaybackPrefetcher.put(repository, targetId, qn, it)
            }
            // 解析期间又切了集:丢弃旧响应,避免把上一集播到新集上。
            if (requestedVideoId != currentVideoId) return
            // 低端机默认清晰度收敛:YouTube 服务端按清晰度降序返回,options.first
            // 是最高档(可达 4K/8K),弱 GPU/弱网直接起播会先卡缓冲。只在自动选档
            // (qn==null)时收敛,手动切清晰度不受影响。
            // 带 qn 重试/断网恢复(切清晰度、多P换页、错误重试)时同样要按已选档
            // 重排:否则服务端降序结果会让 options.first() 变成最高档,把用户收敛
            // 好的 1080P 悄悄重置成 4K/8K(弱机随即再次卡顿/失败)。
            val tuned = if (qn == null) {
                PlaybackTuning.preferInitialOption(result, maxInitialQualityQn)
            } else {
                PlaybackTuning.preferOption(result, qn)
            }
            if (tuned !== result) {
                AppLog.i(
                    "Player",
                    "low-end quality cap: ${result.options.firstOrNull()?.qn} -> " +
                        "${tuned.options.firstOrNull()?.qn}",
                )
            }
            // 记录服务端实际给出的清晰度(可能因会员/片源自动降级),UI 选中态与
            // 后续"重复点当前档位"判断都以它为准。
            currentQn = tuned.options.firstOrNull()?.qn ?: qn
            val firstResolve = resolvedVideoId == null
            resolvedVideoId = requestedVideoId
            _uiState.value = _uiState.value.copy(playbackResult = tuned, isLoading = false)
            AppLog.i(
                "Perf",
                "resolve $requestedVideoId provider=${repository.currentProvider} " +
                    "qn=${tuned.options.firstOrNull()?.qn} in " +
                    "${System.currentTimeMillis() - startedAtMs}ms " +
                    "(prefetched=${prefetched != null}, first=$firstResolve, " +
                    "sinceCreate=${System.currentTimeMillis() - createdAtMs}ms)",
            )
            updateNext()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestedVideoId != currentVideoId) return
            AppLog.e(
                "Player",
                "resolvePlayback failed: ${e.message} " +
                    "(after ${System.currentTimeMillis() - startedAtMs}ms)",
                e,
            )
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
     * 播放连续失败后的清晰度降级:当前档还有更低档时切过去重试,返回是否已切换。
     *
     * 只在"自动重载一次后仍失败"时由 UI 调用(见 PlayerContent.onPlayerError),
     * 避免网络抖动一次就降清晰度;弱网/高码率导致解码或带宽失败时,降一档通常
     * 能立即恢复播放。没有更低档时返回 false,由 UI 展示错误层。
     */
    fun fallbackToLowerQuality(): Boolean {
        val result = _uiState.value.playbackResult ?: return false
        val activeQn = result.options.firstOrNull()?.qn ?: return false
        // YouTube 全部档位在 options 里;B 站单档流 + availableQualities。两者取并集。
        val lower = (result.availableQualities.map { it.qn } + result.options.map { it.qn })
            .filter { it in 1 until activeQn }
            .maxOrNull() ?: return false
        AppLog.i("Player", "playback error fallback: qn $activeQn -> $lower")
        // 降档同样要从服务端刷新签名地址(旧结果里的直链很可能正是失败原因)。
        selectQuality(lower, forceRefresh = true)
        return true
    }

    /**
     * 片尾到达:有下一步则推进(多P下一页换页;合集下一集在同页内无缝切换)。
     * 返回是否真的还有下一步;UI 只负责把 ENDED 边沿传过来。
     */
    fun onPlaybackEnded(): Boolean {
        val next = _uiState.value.next ?: return false
        when (next) {
            is PlaybackNext.Page -> selectPage(next.index)
            is PlaybackNext.Episode -> switchEpisode(next.video)
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
            switchEpisode(collection.items[collection.currentIndex - 1])
            return true
        }
        return false
    }

    private fun updateNext() {
        val state = _uiState.value
        // 解析结果与当前集不匹配(合集切集时队列先到):保持 next=null,等解析完成,
        // 否则会用旧集的 pages 拼出错误的"下一页"。
        if (resolvedVideoId != currentVideoId) {
            if (state.next != null) _uiState.value = state.copy(next = null)
            return
        }
        _uiState.value = state.copy(next = nextPlayback(state.playbackResult, state.collection))
    }
}

class PlayerViewModelFactory(
    private val videoId: String,
    private val initialPage: Int = 0,
    private val initialTitle: String = "",
    private val initialPosterUrl: String? = null,
    private val maxInitialQualityQn: Int = Int.MAX_VALUE,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(
            videoId,
            initialPage = initialPage,
            initialTitle = initialTitle,
            initialPosterUrl = initialPosterUrl,
            maxInitialQualityQn = maxInitialQualityQn,
        ) as T
    }
}
