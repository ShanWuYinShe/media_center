package com.example.mediacenter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ApiException
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.log.AppLog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MediaUiState(
    val query: String = "",
    val categories: List<ContentCategory> = emptyList(),
    // 不再硬编码具体 ID(服务端分类列表会演进,旧 ID 例如 "documentaries"
    // 会导致 404)。初始为 null,等 categories 拉到后由 loadCategoriesAndSearch
    // 自动取列表首项作为默认分类。
    val selectedCategoryId: String? = null,
    val items: List<VideoSummary> = emptyList(),
    val selected: VideoDetails? = null,
    val playing: PlaybackOption? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val canLoadMore: Boolean = false,
    val page: Int = 0,
    val playingVideo: VideoSummary? = null,
    val error: String? = null,
    // 加载失败后正处于"自动重试"退避期:UI 显示失败原因 + 自动重试中,
    // 不再只给一个手动"重试"按钮(老盒子上网络抖动频繁,应自动恢复)。
    val autoRetrying: Boolean = false,
    // 多P视频的分页信息(当前页/总页数/页标题),UI 据此显示选集按钮。
    val currentPages: PlaybackPages? = null,
)

/**
 * 项目级"取消友好"的 runCatching:遇到 [CancellationException] **重抛**,
 * 让 coroutine 取消信号继续向上传播;其他异常打包为 [Result.failure]。
 *
 * 为什么需要:Kotlin 的 [runCatching] 会**吞掉** [CancellationException],
 * 导致旧 job 被 `cancel()` 后仍走 `onFailure` 把"搜索失败"写进 state,
 * 把新 job 刚刚写入的 `loading=true, error=null` 覆盖掉。
 *
 * 用法与 [runCatching] 完全一致,只是更安全。
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

// 单次播放会话内,自动从短期 URL 失效或瞬时 5xx(典型 502 Bad Gateway)
// 恢复的次数上限。
// 超过后不再自动重试,转为向用户展示错误并退回详情页,避免死循环。
private const val MaxPlaybackRecoveryAttempts = 3

// 自动恢复的退避步长:第 n 次重试前等待 n * 该值,给 asset 代理回源(懒解析上游)
// warmup 时间。3 次重试合计约 1.5s + 3s + 4.5s = 9s,避免对仍在预热的网关连发请求。
private const val PlaybackRecoveryBackoffStepMs = 1_500L

// 判定“播放已稳定”的持续时长:只有同一播放地址连续顺畅播放超过该时长,才把
// [playbackRecoveryAttempts] 清零、重新积攒恢复额度。仅凭 STATE_READY 就清零会导致
// “就绪→播放数秒→再次失败”的流(典型:上游分段 403 / 签名中途过期)每轮都把计数器
// 复位,使 [MaxPlaybackRecoveryAttempts] 上限永远失效,陷入“刷新地址→重建播放器”的
// 无限恢复循环,持续吃内存/CPU/网络直至进程被杀、重启回首页。

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = mutableState
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var detailsJob: Job? = null
    private var recoveryJob: Job? = null
    private var playbackPrefetchJob: Job? = null
    // 首页分类加载(loadCategoriesAndSearch)专用 job:失败自动重试期间可被
    // 用户手动 retry 安全替换,不会出现两个重试循环并存。
    private var loadHomeJob: Job? = null
    // 播放地址预取缓存:列表可见视频在后台提前解析,用户点详情时直接可用。
    private val playbackCache = mutableMapOf<String, PlaybackResult>()
    private val prefetchedPlaybackIds = mutableSetOf<String>()
    // 在列表末尾按"下一个"时,记录续播来源 id:loadMore 拉到新一页后,
    // 在其 onSuccess 里自动续播该来源的下一条,避免自动续播在分页边界"断档"。
    private var pendingAutoAdvanceFromId: String? = null
    // 播放发生短期 URL 失效或瞬时 5xx(典型 502 Bad Gateway)时,自动刷新
    // 地址 / 切换到下一清晰度重试,而不是直接把用户弹回详情页。计数器在新播放开始
    // 或播放“持续稳定”一段时间后清零(见 [playbackSustained]),封顶
    // [MaxPlaybackRecoveryAttempts] 次后转为展示错误。注意:不能仅凭 STATE_READY
    // 就清零,否则“就绪即失败”的流会绕过上限陷入无限恢复循环。
    private var playbackRecoveryAttempts = 0
    private var playbackSession = 0L
    // 当前已缓存的播放清晰度 qn,选不同清晰度时需重新请求。
    private var currentPlaybackQn: Int = 0

    // 分类级内存缓存:再次进入已加载过的分类时,立即展示缓存内容,不再清空列表、
    // 不再 loading 转圈阻塞。仅当缓存过期(超过 [CategoryCacheTtlMs])时在后台静默刷新,
    // 且刷新只更新缓存、不改动当前可见列表/焦点,保证异步加载永不把用户弹离当前页面。
    private data class CategoryCacheEntry(
        val items: List<VideoSummary>,
        val page: Int,
        val canLoadMore: Boolean,
        val timestamp: Long,
    )

    private val categoryListCache = mutableMapOf<String, CategoryCacheEntry>()
    private var categoryRefreshJob: Job? = null

    private fun cacheCategoryList(
        categoryId: String,
        items: List<VideoSummary>,
        page: Int,
        canLoadMore: Boolean,
    ) {
        if (items.isEmpty()) return
        categoryListCache[categoryId] =
            CategoryCacheEntry(items, page, canLoadMore, System.currentTimeMillis())
    }

    // 预取列表可见视频的播放地址:浏览时后台提前解析 B 站 API,
    // 用户点详情时直接可用,无需网络等待。按视频 id 去重避免重复请求。
    //
    // 限制:只预取前 [MAX_PREFETCH] 条(用户不会逐一点开全部可见项),并发用信号量
    // 限到 [PREFETCH_CONCURRENCY](每条 playbackOptions 是 3 个串行网络调用,无限制并发
    // 会在长列表上打满网络/内存)。
    private fun prefetchVisiblePlayback(videos: List<VideoSummary>) {
        playbackPrefetchJob?.cancel()
        val candidates = videos
            .asSequence()
            .distinctBy { it.id }
            .filter { it.id !in prefetchedPlaybackIds }
            .take(MAX_PREFETCH)
            .toList()
        if (candidates.isEmpty()) return
        playbackPrefetchJob = viewModelScope.launch {
            val sem = Semaphore(PREFETCH_CONCURRENCY)
            coroutineScope {
                candidates.map { video ->
                    launch {
                        if (video.id in prefetchedPlaybackIds) return@launch
                        val details = VideoDetails(
                            id = video.id, title = video.title,
                            description = video.description,
                            thumbnailUrl = video.thumbnailUrl,
                            durationSeconds = video.durationSeconds,
                        )
                        sem.withPermit {
                            runCatchingCancellable { repository.playbackOptions(details) }
                                .onSuccess { result ->
                                    playbackCache[video.id] = result
                                    prefetchedPlaybackIds.add(video.id)
                                }
                        }
                    }
                }.joinAll()
            }
        }
    }

    init {
        loadCategoriesAndSearch()
    }

    fun search(
        query: String,
        // 关键词搜索与分类浏览都限定在当前选中分类内——服务端无全局搜索接口,
        // 客户端聚合过滤依赖 categoryId;传 null 会直接返回空结果。
        categoryId: String? = mutableState.value.selectedCategoryId,
    ) {
        val current = mutableState.value
        val silentRefresh = current.isContentScreen
        if (silentRefresh &&
            (query != current.query || categoryId != current.selectedCategoryId)
        ) {
            AppLog.i(
                "Search",
                "ignore offscreen search while content is visible: " +
                    "queryChanged=${query != current.query} categoryChanged=${categoryId != current.selectedCategoryId}",
            )
            return
        }
        // 防御:服务端要求 q / category 至少一个,空 + null 直接拒绝避免反复 400。
        if (query.isBlank() && categoryId == null) {
            if (!silentRefresh) {
                mutableState.update {
                    it.copy(loading = false, error = "请选择一个分类或输入关键词")
                }
            } else {
                AppLog.w("Search", "ignore invalid silent refresh while content is visible")
            }
            return
        }
        if (!silentRefresh) {
            detailsJob?.cancel()
            recoveryJob?.cancel()
            loadMoreJob?.cancel()
            pendingAutoAdvanceFromId = null
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 失败自动重试:非静默场景下,IO/瞬时错误最多重试
            // MAX_SEARCH_AUTO_RETRIES 次(退避见 SEARCH_AUTO_RETRY_BACKOFF_MS),
            // 退避期 UI 显示"自动重试中";重试期间用户切走(进入详情/播放页)
            // 则放弃重试,把错误静默丢弃,不覆盖新页面的状态。
            var attempt = 0
            while (true) {
                val isSilent = mutableState.value.isContentScreen
                mutableState.update {
                    if (isSilent || it.isContentScreen) {
                        it.copy(loadMoreError = null)
                    } else {
                        it.copy(
                            query = query,
                            selectedCategoryId = categoryId,
                            items = emptyList(),
                            loading = true,
                            loadingMore = false,
                            loadMoreError = null,
                            canLoadMore = false,
                            page = 1,
                            error = null,
                            autoRetrying = false,
                            selected = null,
                            playing = null,
                            playingVideo = null,
                        )
                    }
                }
                runCatchingCancellable {
                    repository.search(query, categoryId, page = 1, pageSize = PageSize)
                }
                    .onSuccess { response ->
                        mutableState.update { state ->
                            state.copy(
                                items = response.items,
                                loading = if (isSilent || state.isContentScreen) state.loading else false,
                                // 关键词搜索是一次性客户端聚合(已拉取多页),不分页;仅分类浏览才支持 loadMore。
                                canLoadMore = query.isBlank() && response.items.size >= PageSize,
                                page = 1,
                                loadMoreError = null,
                            )
                        }
                        // 分类浏览(空 query + 指定分类)结果写入分类缓存,供下次秒开。
                        if (query.isBlank() && categoryId != null) {
                            cacheCategoryList(
                                categoryId,
                                response.items,
                                page = 1,
                                canLoadMore = response.items.size >= PageSize,
                            )
                        }
                        // 后台预取列表视频的播放地址,用户点详情时直接可用。
                        prefetchVisiblePlayback(response.items)
                        return@launch
                    }
                    .onFailure { error ->
                        if (isSilent) {
                            AppLog.w("Search", "silent refresh failed: ${error.message}", error)
                            return@launch
                        }
                        if (attempt < MAX_SEARCH_AUTO_RETRIES && isAutoRetryable(error)) {
                            attempt++
                            val backoff = SEARCH_AUTO_RETRY_BACKOFF_MS[attempt - 1]
                            AppLog.w("Search", "auto retry ${attempt}/$MAX_SEARCH_AUTO_RETRIES in ${backoff}ms: ${error.message}", error)
                            mutableState.update { state ->
                                if (state.isContentScreen) {
                                    state
                                } else {
                                    state.copy(
                                        loading = false,
                                        error = error.message ?: "搜索失败",
                                        autoRetrying = true,
                                    )
                                }
                            }
                            delay(backoff)
                        } else {
                            mutableState.update { state ->
                                if (state.isContentScreen) {
                                    AppLog.w("Search", "discard search error while content is visible: ${error.message}", error)
                                    state
                                } else {
                                    state.copy(loading = false, autoRetrying = false, error = error.message ?: "搜索失败")
                                }
                            }
                            return@launch
                        }
                    }
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || current.loadingMore || !current.canLoadMore || current.loadMoreError != null) return
        val nextPage = current.page + 1
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingMore = true, loadMoreError = null, error = null) }
            runCatchingCancellable {
                repository.search(
                    current.query,
                    current.selectedCategoryId,
                    page = nextPage,
                    pageSize = PageSize,
                )
            }
                .onSuccess { response ->
                    mutableState.update { state ->
                        val merged = (state.items + response.items).distinctBy { it.id }
                        state.copy(
                            items = merged,
                            loadingMore = false,
                            loadMoreError = null,
                            canLoadMore = response.items.size >= PageSize,
                            page = nextPage,
                        )
                    }
                    // 把翻页后的完整列表写回分类缓存,下次进入该分类时保留已加载的多页内容。
                    if (current.query.isBlank() && current.selectedCategoryId != null) {
                        val latest = mutableState.value
                        cacheCategoryList(
                            current.selectedCategoryId,
                            latest.items,
                            page = nextPage,
                            canLoadMore = response.items.size >= PageSize,
                        )
                    }
                    // 后台预取新增视频的播放地址。
                    prefetchVisiblePlayback(response.items)
                    // 若是"末尾按下一个/自动续播触发的 loadMore",新页到手后
                    // 继续把焦点视频续播下去,补上分页边界的断档。
                    val pendingId = pendingAutoAdvanceFromId
                    if (pendingId != null) {
                        pendingAutoAdvanceFromId = null
                        val newItems = mutableState.value.items
                        val i = newItems.indexOfFirst { it.id == pendingId }
                        if (i >= 0 && i + 1 < newItems.size) {
                            playVideoAt(i + 1, newItems)
                        }
                    }
                }
                .onFailure { error ->
                    // 续播依赖的分页加载失败:清掉待续播标记,避免悬挂。
                    pendingAutoAdvanceFromId = null
                    mutableState.update {
                        it.copy(
                            loadingMore = false,
                            loadMoreError = error.message ?: "加载更多失败",
                        )
                    }
                }
        }
    }

    fun retryLoadMore() {
        if (mutableState.value.loadMoreError == null) return
        mutableState.update { it.copy(loadMoreError = null) }
        loadMore()
    }

    fun selectCategory(category: ContentCategory) {
        // 已选中的分类不再重复搜索，避免焦点自动切换时反复触发无用请求
        if (category.id == mutableState.value.selectedCategoryId) return
        // 防御:播放或详情页期间不应切分类(applyCachedCategory/search 会清空
        // playing/selected,把用户弹回主页)。上游调用者(chip/防抖)有条件检查,
        // 但 ViewModel 层再加一道兜底,杜绝任何遗漏路径。
        if (mutableState.value.isContentScreen) {
            AppLog.w("Nav", "ignore selectCategory while content screen is visible")
            return
        }
        val cached = categoryListCache[category.id]
        if (cached != null && cached.items.isNotEmpty()) {
            // 命中缓存:立即展示,不清空、不 loading 转圈。
            applyCachedCategory(category.id, cached)
            // 缓存过期才后台静默刷新(只更新缓存,不动当前可见列表/焦点)。
            if (System.currentTimeMillis() - cached.timestamp > CategoryCacheTtlMs) {
                refreshCategoryInBackground(category.id)
            }
        } else {
            // 无缓存(首次进入):正常阻塞加载。
            search(query = "", categoryId = category.id)
        }
    }

    private fun applyCachedCategory(categoryId: String, cached: CategoryCacheEntry) {
        // 取消上一个分类正在进行的列表/详情请求,直接切到缓存内容,绝不进 loading。
        detailsJob?.cancel()
        recoveryJob?.cancel()
        loadMoreJob?.cancel()
        searchJob?.cancel()
        categoryRefreshJob?.cancel()
        pendingAutoAdvanceFromId = null
        mutableState.update {
            it.copy(
                query = "",
                selectedCategoryId = categoryId,
                items = cached.items,
                loading = false,
                loadingMore = false,
                loadMoreError = null,
                canLoadMore = cached.canLoadMore,
                page = cached.page,
                error = null,
                selected = null,
                playing = null,
                playingVideo = null,
            )
        }
    }

    private fun refreshCategoryInBackground(categoryId: String) {
        categoryRefreshJob?.cancel()
        categoryRefreshJob = viewModelScope.launch {
            runCatchingCancellable {
                repository.search("", categoryId, page = 1, pageSize = PageSize)
            }
                .onSuccess { response ->
                    // 只刷新缓存,不触碰当前可见列表与焦点;新数据下次进入该分类时生效。
                    // 这样后台刷新永远不会把用户从当前网格/页面弹走。
                    cacheCategoryList(
                        categoryId,
                        response.items,
                        page = 1,
                        canLoadMore = response.items.size >= PageSize,
                    )
                }
                .onFailure { error ->
                    AppLog.w("Search", "background category refresh failed: ${error.message}", error)
                }
        }
    }

    fun openDetails(video: VideoSummary) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            val details = repository.details(video)
            // 如果列表预取已缓存该视频的播放地址,直接写入 selected。
            val cached = playbackCache[video.id]
            if (cached != null) {
                mutableState.update {
                    it.copy(selected = details.copy(playbackOptions = cached.options), loading = false,
                            currentPages = cached.pages)
                }
                return@launch
            }
            mutableState.update { it.copy(selected = details, loading = false) }
            runCatchingCancellable { repository.playbackOptions(details) }
                .onSuccess { result ->
                    currentPlaybackQn = result.options.firstOrNull()?.qn ?: 0
                    mutableState.update { state ->
                        if (state.selected?.id != details.id) return@update state
                        state.copy(selected = state.selected.copy(playbackOptions = result.options),
                                   currentPages = result.pages)
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selected?.id != details.id) return@update state
                        state.copy(error = error.message ?: "解析播放地址失败")
                    }
                }
        }
    }

    /**
     * 多P视频选页:切换到指定页码,解析该页播放地址后更新 selected。
     */
    fun selectPage(pageIndex: Int) {
        val current = mutableState.value
        val selected = current.selected ?: return
        val pages = current.currentPages ?: return
        if (pageIndex < 0 || pageIndex >= pages.totalPages) return
        if (pageIndex == pages.currentPage) return
        switchPlaybackPage(selected.id, pageIndex, pages)
    }

    fun play(stream: PlaybackOption) {
        playbackSession++
        playbackRecoveryAttempts = 0
        val current = mutableState.value
        val selected = current.selected
        val video = current.playingVideo
            ?: selected?.let { sel -> current.items.find { it.id == sel.id } }
        AppLog.i("Playback", "play requested video=${selected?.id ?: "<none>"} option=${stream.id} kind=${stream.kind}")
        if (selected == null) {
            AppLog.w("Playback", "play() called with selected==null, ignoring to avoid unrecoverable state")
            return
        }
        // 选不同清晰度时重新请求 playurl,使用 durl(完整 MP4)播放
        if (stream.qn > 0 && stream.qn != currentPlaybackQn) {
            switchQuality(selected, stream, video)
            return
        }
        // 如果 playbackOptions 已由 openDetails 后台自动解析好,直接取用,
        // 避免每次点播放都重调 B 站 API(耗时 5-15s)。
        val cached = selected.playbackOptions
        if (cached.isNotEmpty()) {
            val match = cached.firstOrNull { it.id == stream.id }
                ?: cached.firstOrNull { it.label == stream.label }
            if (match != null) {
                mutableState.update { it.copy(playing = match, playingVideo = video) }
                return
            }
        }
        detailsJob?.cancel()
        recoveryJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.playbackOptions(selected) }
                .onSuccess { result ->
                    val options = result.options
                    val freshStream = options.firstOrNull { it.id == stream.id }
                        ?: options.firstOrNull { it.label == stream.label }
                        ?: options.firstOrNull()
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) {
                            state.copy(loading = false)
                        } else if (freshStream == null) {
                            state.copy(
                                loading = false,
                                playing = null,
                                error = "该视频暂无可播放清晰度",
                            )
                        } else {
                            state.copy(
                                loading = false,
                                selected = state.selected.copy(playbackOptions = options),
                                playing = freshStream,
                                playingVideo = video ?: state.playingVideo,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) {
                            state.copy(loading = false)
                        } else {
                            state.copy(
                                loading = false,
                                playing = null,
                                error = error.message ?: "刷新播放地址失败",
                            )
                        }
                    }
                }
        }
    }

    // 切换到指定清晰度后播放，重新请求 playurl 获取该清晰度的 durl。
    private fun switchQuality(selected: VideoDetails, stream: PlaybackOption, video: VideoSummary?) {
        detailsJob?.cancel()
        recoveryJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.playbackOptions(selected, stream.qn) }
                .onSuccess { result ->
                    currentPlaybackQn = stream.qn
                    val fresh = result.options.firstOrNull { it.id == stream.id }
                        ?: result.options.firstOrNull { it.label == stream.label }
                        ?: result.options.firstOrNull()
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) return@update state
                        state.copy(
                            loading = false,
                            selected = state.selected.copy(playbackOptions = result.options),
                            playing = fresh,
                            playingVideo = video ?: state.playingVideo,
                            currentPages = result.pages,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) return@update state
                        state.copy(loading = false, error = error.message ?: "切换清晰度失败")
                    }
                }
        }
    }

    /**
     * 播放“持续稳定”信号:同一播放地址已连续顺畅播放超过稳定窗口(由 UI 层计时,
     * 见 MainActivity 的稳定计时器)时调用,表示这条流确实可用,可以把恢复额度清零、
     * 重新积攒。**不要**在 STATE_READY 一就绪时就调用——“就绪即失败”的流会借此
     * 反复复位计数器,使 [MaxPlaybackRecoveryAttempts] 失效并陷入无限恢复循环。
     */
    fun playbackSustained(streamId: String) {
        if (mutableState.value.playing?.id == streamId) {
            if (playbackRecoveryAttempts > 0) {
                AppLog.i("Playback", "stream $streamId sustained, reset recovery budget (was $playbackRecoveryAttempts)")
            }
            playbackRecoveryAttempts = 0
        }
    }

    fun playbackFailed(
        message: String,
        recoverable: Boolean = false,
    ) {
        val current = mutableState.value
        val failedOption = current.playing
        val fallbackSelected = current.selected
            ?: current.playingVideo?.toPlaybackFailureDetails(failedOption)
        // 短期 URL 失效 / 瞬时 5xx / CDN 断流且仍有恢复额度时,自动刷新播放地址
        // 并尝试切换清晰度续播,不打断用户;额度耗尽或非瞬时错误才落到错误态。
        if (recoverable && fallbackSelected != null && failedOption != null &&
            playbackRecoveryAttempts < MaxPlaybackRecoveryAttempts
        ) {
            if (current.selected == null) {
                mutableState.update { it.copy(selected = fallbackSelected) }
            }
            playbackRecoveryAttempts++
            AppLog.w(
                "Playback",
                "failed (recoverable) video=${fallbackSelected.id} option=${failedOption.id} " +
                    "-> recover attempt $playbackRecoveryAttempts/$MaxPlaybackRecoveryAttempts: $message",
            )
            recoverPlayback(
                session = playbackSession,
                videoId = fallbackSelected.id,
                failedOption = failedOption,
                fallbackMessage = message,
            )
            return
        }
        AppLog.w(
            "Playback",
            "failed video=${fallbackSelected?.id} option=${failedOption?.id} recoverable=$recoverable " +
                "attempts=$playbackRecoveryAttempts -> show error, back to ${if (fallbackSelected != null) "details" else "home"}: $message",
        )
        playbackRecoveryAttempts = 0
        // 错误处理:清 playing 保留 selected(无论是原本就有,还是 fallback 的占位),
        // 让 UI 留在详情页显示错误原因。一次 update 避免详情页先无错误渲染再闪现错误。
        mutableState.update {
            it.copy(
                selected = fallbackSelected ?: it.selected,
                playing = null,
                error = message,
            )
        }
    }

    /**
     * 可恢复播放失败后的自动恢复。先按 [playbackRecoveryAttempts] 做指数退避,给
     * 上游短期 URL 刷新留出时间,再重新拉取 [videoId] 的播放选项拿到新鲜地址。
     * 选档策略:前几次刷新**同一清晰度**(多为签名过期,同源重试最可能恢复);
     * 最后一次额度才切到下一档绕开可能彻底坏掉的源。仍取不到可播放
     * 选项时落错误态。退避期间用户离开播放页或切换了清晰度则放弃,不写回过期状态。
     */
    private fun recoverPlayback(
        session: Long,
        videoId: String,
        failedOption: PlaybackOption,
        fallbackMessage: String,
    ) {
        val attempt = playbackRecoveryAttempts
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            delay(attempt.coerceIn(1, MaxPlaybackRecoveryAttempts) * PlaybackRecoveryBackoffStepMs)
            val afterDelay = mutableState.value
            // 退避期间用户离开播放页或切换了清晰度 → 放弃恢复
            if (session != playbackSession ||
                afterDelay.selected?.id != videoId ||
                afterDelay.playing?.id != failedOption.id
            ) {
                AppLog.d("Playback", "recover attempt $attempt aborted: user left or switched stream")
                mutableState.update { it.copy(loading = false) }
                return@launch
            }
            val currentSelected = afterDelay.selected
                ?: return@launch
            val qn = currentPlaybackQn
            runCatchingCancellable {
                if (qn > 0) repository.playbackOptions(currentSelected, qn)
                else repository.playbackOptions(currentSelected)
            }.onSuccess { result ->
                    val options = result.options
                    val sameIdx = options.indexOfFirst { it.id == failedOption.id }
                    val candidate = when {
                        options.isEmpty() -> null
                        // 非最终尝试:重试同一清晰度(多为签名过期/回源 warmup)
                        attempt < MaxPlaybackRecoveryAttempts && sameIdx >= 0 -> options[sameIdx]
                        // 最终尝试或同档已消失:切到下一档
                        sameIdx in 0 until options.lastIndex -> options[sameIdx + 1]
                        // 同档是列表末尾或已消失:回绕到首档
                        else -> options.first()
                    }
                    AppLog.i(
                        "Playback",
                        "recover attempt $attempt fetched ${options.size} options, " +
                            "candidate=${candidate?.id ?: "<none>"}",
                    )
                    mutableState.update { state ->
                        if (session != playbackSession ||
                            state.selected?.id != videoId ||
                            state.playing?.id != failedOption.id
                        ) {
                            state.copy(loading = false)
                        } else if (candidate == null) {
                            state.copy(
                                loading = false,
                                playing = null,
                                error = fallbackMessage,
                            )
                        } else {
                            state.copy(
                                loading = false,
                                selected = state.selected.copy(playbackOptions = options),
                                playing = candidate,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    AppLog.w("Playback", "recover attempt $attempt fetch failed for video=$videoId", error)
                    mutableState.update { state ->
                        if (session != playbackSession ||
                            state.selected?.id != videoId ||
                            state.playing?.id != failedOption.id
                        ) {
                            state.copy(loading = false)
                        } else {
                            state.copy(
                                loading = false,
                                playing = null,
                                error = error.message ?: fallbackMessage,
                            )
                        }
                    }
                }
        }
    }

    fun refreshSelectedPlayback() {
        val selected = mutableState.value.selected ?: return
        detailsJob?.cancel()
        recoveryJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.playbackOptions(selected) }
                .onSuccess { result ->
                    val options = result.options
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) {
                            state.copy(loading = false)
                        } else {
                            state.copy(
                                loading = false,
                                selected = state.selected.copy(playbackOptions = options),
                                error = if (options.isEmpty()) "该视频暂无可播放清晰度" else null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selected?.id != selected.id) {
                            state.copy(loading = false)
                        } else {
                            state.copy(
                                loading = false,
                                error = error.message ?: "刷新播放地址失败",
                            )
                        }
                    }
                }
        }
    }

    /**
     * 播放“下一个”:当前多P视频有下一页时切页,否则跳转列表下一个视频。
     * 已是列表末尾时触发 loadMore 自动续播。
     */
    fun playNext() {
        val current = mutableState.value
        val curId = current.playingVideo?.id ?: current.selected?.id ?: return
        val items = current.items
        // 多P切页:优先用 UI state 的 currentPages(权威来源),回退到预取缓存。
        val pages = current.currentPages ?: playbackCache[curId]?.pages
        if (pages != null && pages.totalPages > 1) {
            val nextPage = pages.currentPage + 1
            if (nextPage < pages.totalPages) {
                AppLog.i("Nav", "playNext -> page $nextPage/${pages.totalPages} for $curId")
                switchPlaybackPage(curId, nextPage, pages)
                return
            }
        }
        // 多P已播完或单P视频:跳到列表下一个视频。
        val idx = items.indexOfFirst { it.id == curId }
        if (idx < 0) return
        val nextIndex = idx + 1
        if (nextIndex >= items.size) {
            if (current.canLoadMore) {
                pendingAutoAdvanceFromId = curId
                if (!current.loadingMore) loadMore()
            }
            return
        }
        pendingAutoAdvanceFromId = null
        AppLog.i("Nav", "playNext to index $nextIndex of ${items.size}")
        playVideoAt(nextIndex, items)
    }

    /**
     * 播放“上一个”:只在当前已加载列表内回退,不跨页反向加载历史页。
     */
    fun playPrevious() {
        val current = mutableState.value
        val curId = current.playingVideo?.id ?: current.selected?.id ?: return
        val items = current.items
        // 多P切页:优先用 UI state 的 currentPages(权威来源),回退到预取缓存。
        val pages = current.currentPages ?: playbackCache[curId]?.pages
        if (pages != null && pages.totalPages > 1) {
            val prevPage = pages.currentPage - 1
            if (prevPage >= 0) {
                AppLog.i("Nav", "playPrevious -> page $prevPage/${pages.totalPages} for $curId")
                switchPlaybackPage(curId, prevPage, pages)
                return
            }
        }
        // 多P已在第一集或单P视频:跳到列表上一个视频。
        val idx = items.indexOfFirst { it.id == curId }
        if (idx <= 0) return
        pendingAutoAdvanceFromId = null
        AppLog.i("Nav", "playPrevious to index ${idx - 1} of ${items.size}")
        playVideoAt(idx - 1, items, prefetchNextPage = false)
    }

    /**
     * 切换多P视频到指定分页:解析该页播放地址并同步更新 UI state(playing +
     * currentPages)与预取缓存。供 selectPage/playNext/playPrevious 共用,
     * 确保 currentPages 和 playbackCache 始终一致。
     */
    private fun switchPlaybackPage(bvid: String, pageIndex: Int, pages: PlaybackPages) {
        val updatedPages = pages.copy(currentPage = pageIndex)
        playbackCache[bvid]?.let { cached -> playbackCache[bvid] = cached.copy(pages = updatedPages) }
        playbackSession++
        detailsJob?.cancel()
        recoveryJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, currentPages = updatedPages) }
            runCatchingCancellable { repository.resolvePagePlayback(bvid, pageIndex) }
                .onSuccess { options ->
                    val option = options.firstOrNull()
                    mutableState.update { state ->
                        if (state.selected?.id != bvid) return@update state
                        state.copy(
                            loading = false,
                            selected = state.selected.copy(playbackOptions = options),
                            playing = option,
                            currentPages = state.currentPages?.copy(currentPage = pageIndex) ?: updatedPages,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selected?.id != bvid) return@update state
                        state.copy(loading = false, currentPages = pages, error = error.message ?: "切换分页失败")
                    }
                }
        }
    }

    /**
     * 拉取列表中第 [index] 条视频的详情并以默认(最高)清晰度直接开播。
     * 失败时**清空 playing**:让 UI 从播放页退回详情页,而不是把用户困在
     * 一帧"已结束/卡死"的播放画面上(播放页本身不渲染错误态)。
     */
    private fun playVideoAt(
        index: Int,
        items: List<VideoSummary>,
        prefetchNextPage: Boolean = true,
    ) {
        playbackRecoveryAttempts = 0
        playbackSession++
        val nextVideo = items[index]
        val session = playbackSession
        AppLog.i("Playback", "playVideoAt index=$index video=${nextVideo.id}")
        // 临近列表末尾时顺便预取下一页,保证“下一个”能持续可用
        if (prefetchNextPage && index >= items.size - 3) loadMore()
        detailsJob?.cancel()
        recoveryJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable {
                val details = repository.details(nextVideo)
                val result = repository.playbackOptions(details)
                Pair(details.copy(playbackOptions = result.options), result.pages)
            }
                .onSuccess { (details, pages) ->
                    if (session != playbackSession) return@onSuccess
                    val option = details.playbackOptions.firstOrNull()
                    if (option == null) {
                        AppLog.w("Playback", "video ${details.id} has no playable option")
                        mutableState.update {
                            it.copy(
                                loading = false,
                                selected = details,
                                playingVideo = nextVideo,
                                playing = null,
                                currentPages = pages,
                                error = "该视频暂无可播放清晰度",
                            )
                        }
                    } else {
                        mutableState.update {
                            it.copy(
                                loading = false,
                                selected = details,
                                playingVideo = nextVideo,
                                playing = option,
                                currentPages = pages,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (session != playbackSession) return@onFailure
                    AppLog.w("Playback", "playVideoAt failed for ${nextVideo.id}: ${error.message}")
                    mutableState.update {
                        it.copy(
                            loading = false,
                            selected = nextVideo.toPlaybackFailureDetails(null),
                            playingVideo = nextVideo,
                            playing = null,
                            error = error.message ?: "播放下一个失败",
                        )
                    }
                }
        }
    }

    fun retry() {
        val current = mutableState.value
        if (current.items.isNotEmpty() && current.loadMoreError != null) {
            retryLoadMore()
            return
        }
        // 当 categories 为空时,意味着首次加载也失败了,需要从分类开始重试;
        // 否则仅重试当前 search,避免覆盖已经加载好的分类数据。
        if (current.categories.isEmpty()) {
            loadCategoriesAndSearch()
        } else {
            search(current.query)
        }
    }

    /**
     * 是否值得自动重试:网络 IO 错误(含 MediaApi 包装后的确定性诊断 IOException)
     * 与瞬时 5xx。业务错误(400/401 等非瞬时 ApiException)重试不会成功,直接展示。
     */
    private fun isAutoRetryable(error: Throwable?): Boolean {
        if (error == null) return false
        return error is IOException || (error is ApiException && error.isTransient)
    }

    fun back() {
        // 取消可能"迟到回写"的任务,否则:在播放页按 ↓(下一个)后立刻按 BACK,
        // back() 把 playing 清空(回到详情),但仍在跑的 detailsJob 完成后又把
        // playing 重新写成非空 → 用户被强行拽回播放页。这与"BACK 漏拦截"同类:
        // BACK 的效果被异步写默默撤销。同理清掉待续播标记,避免 loadMore 完成后
        // 触发自动续播再次跳回播放页。
        // 注意:back() **不清 error**——主动 Back 和错误触发 Back 都保留错误信息,
        // 让用户在详情页/主页上能看到错误原因。
        detailsJob?.cancel()
        recoveryJob?.cancel()
        playbackSession++
        pendingAutoAdvanceFromId = null
        val before = mutableState.value
        AppLog.i("Nav", "back from " + when {
            before.playing != null -> "player -> details"
            before.selected != null -> "details -> home"
            else -> "home"
        })
        mutableState.update {
            // 取消 detailsJob 后,其内部的 loading=false 清理可能不再执行(协程在
            // recoverPlayback 的退避 delay 中被取消)。BACK 已无在途加载需要等待,
            // 这里顺手把 loading 复位,避免返回详情/首页后卡在"正在加载/重试"提示上。
            when {
                it.playing != null -> it.copy(playing = null, loading = false)
                it.selected != null -> it.copy(selected = null, loading = false)
                else -> it
            }
        }
    }

    /**
     * 清除播放错误态,让 [MainActivity.renderPlayer] 用同一地址重建播放器重试。
     * 仅在播放页(无 details 信息可退,fallbackSelected 为 null)的错误 overlay 中使用。
     */
    fun dismissPlaybackError() {
        playbackRecoveryAttempts = 0
        mutableState.update { it.copy(error = null) }
    }

    private fun loadCategoriesAndSearch() {
        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            // 启动时拉取 provider 凭证(如 bilibili SESSDATA),失败时静默降级。
            repository.refreshCredentials()
            // 分类加载失败自动重试:最多 MAX_HOME_AUTO_RETRIES 次(退避见
            // HOME_AUTO_RETRY_BACKOFF_MS),覆盖老盒子"开机 WiFi 未就绪 /
            // 网络闪断"的场景;重试期间用户切走则放弃,最终失败才落错误态。
            var attempt = 0
            while (true) {
                // categories 失败时不调 search()。避免:网络持续不通 →
                // categories 失败 + search(query="", null) 也 400 失败 →
                // 用户陷入"重试 → 失败 → 重试"的死循环。
                val silentRefresh = mutableState.value.isContentScreen
                mutableState.update {
                    if (silentRefresh) {
                        it.copy(loadMoreError = null)
                    } else {
                        it.copy(loading = true, loadMoreError = null, error = null, autoRetrying = false)
                    }
                }
                val categoriesResult = runCatchingCancellable { repository.categories() }
                val categories = categoriesResult.getOrNull()
                if (categories != null) {
                    // categories 拿到了,确定默认选中:
                    //   - 若之前已选 ID 仍存在,沿用(用户切了 chip 又触发 retry 不会被重置)
                    //   - 否则落到列表首项
                    mutableState.update { current ->
                        val validId = current.selectedCategoryId
                            ?.takeIf { id -> categories.any { it.id == id } }
                        val nextId = validId ?: categories.firstOrNull()?.id
                        current.copy(
                            categories = categories,
                            selectedCategoryId = if (silentRefresh || current.isContentScreen) {
                                current.selectedCategoryId
                            } else {
                                nextId
                            },
                        )
                    }
                    search(mutableState.value.query)
                    return@launch
                }
                val error = categoriesResult.exceptionOrNull()
                if (silentRefresh) {
                    AppLog.w(
                        "Search",
                        "silent category refresh failed: ${error?.message}",
                        error,
                    )
                    return@launch
                }
                if (attempt >= MAX_HOME_AUTO_RETRIES || !isAutoRetryable(error)) {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            autoRetrying = false,
                            error = error?.message ?: "加载分类失败",
                        )
                    }
                    return@launch
                }
                attempt++
                val backoff = HOME_AUTO_RETRY_BACKOFF_MS[attempt - 1]
                AppLog.w(
                    "Search",
                    "categories load failed, auto retry $attempt/$MAX_HOME_AUTO_RETRIES in ${backoff}ms: ${error?.message}",
                    error,
                )
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = error?.message ?: "加载分类失败",
                        autoRetrying = true,
                    )
                }
                delay(backoff)
            }
        }
    }

    private val MediaUiState.isContentScreen: Boolean
        get() = selected != null || playing != null

    private fun VideoSummary.toPlaybackFailureDetails(failedOption: PlaybackOption?): VideoDetails {
        return VideoDetails(
            id = id,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            playbackOptions = listOfNotNull(failedOption),
        )
    }
}

private const val PageSize = 18

// 分类缓存有效期:10 分钟内再次进入同一分类直接用缓存,不触发任何网络请求;
// 超过则在后台静默刷新缓存(不阻塞、不改动当前界面)。
private const val CategoryCacheTtlMs = 10 * 60 * 1000L

// 播放地址预取:只预取前 N 条(用户不会逐一点开全部可见项),并发限到 M。
// 每个 playbackOptions 是 3 个串行网络调用;无限制并发会在长列表上打满网络/内存。
private const val MAX_PREFETCH = 4
private const val PREFETCH_CONCURRENCY = 2

// 首页分类加载失败自动重试:最多额外重试 MAX_HOME_AUTO_RETRIES 次,
// 退避 2s/4s/8s。老 TV 盒子开机时 WiFi/路由未就绪很常见,单次失败直接
// 弹错误卡住首页体验差;重试期 UI 显示"自动重试中",耗尽后才落手动重试。
private const val MAX_HOME_AUTO_RETRIES = 3
private val HOME_AUTO_RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L)

// 分类切换 / 关键词搜索失败自动重试:2 次,退避 2s/4s(比首页少,
// 因为切分类是用户主动操作,等待感知更强)。
private const val MAX_SEARCH_AUTO_RETRIES = 2
private val SEARCH_AUTO_RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L)

class MediaViewModelFactory(
    private val repository: MediaRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MediaViewModel(repository) as T
    }
}
