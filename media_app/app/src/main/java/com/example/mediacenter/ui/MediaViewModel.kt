package com.example.mediacenter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val canLoadMore: Boolean = false,
    val page: Int = 0,
    val playingVideo: VideoSummary? = null,
    val error: String? = null,
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

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = mutableState
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var detailsJob: Job? = null
    // 在列表末尾按"下一个"时,记录续播来源 id:loadMore 拉到新一页后,
    // 在其 onSuccess 里自动续播该来源的下一条,避免自动续播在分页边界"断档"。
    private var pendingAutoAdvanceFromId: String? = null

    init {
        loadCategoriesAndSearch()
    }

    fun search(
        query: String,
        categoryId: String? = if (query.isBlank()) mutableState.value.selectedCategoryId else null,
    ) {
        // 防御:服务端要求 q / category 至少一个,空 + null 直接拒绝避免反复 400。
        if (query.isBlank() && categoryId == null) {
            mutableState.update {
                it.copy(loading = false, error = "请选择一个分类或输入关键词")
            }
            return
        }
        detailsJob?.cancel()
        loadMoreJob?.cancel()
        searchJob?.cancel()
        pendingAutoAdvanceFromId = null
        searchJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    query = query,
                    selectedCategoryId = categoryId,
                    items = emptyList(),
                    loading = true,
                    loadingMore = false,
                    canLoadMore = false,
                    page = 1,
                    error = null,
                    selected = null,
                    playing = null,
                    playingVideo = null,
                )
            }
            runCatchingCancellable {
                repository.search(query, categoryId, page = 1, pageSize = PageSize)
            }
                .onSuccess { response ->
                    mutableState.update {
                        it.copy(
                            items = response.items,
                            loading = false,
                            canLoadMore = response.items.size >= PageSize,
                            page = 1,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(loading = false, error = error.message ?: "搜索失败")
                    }
                }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || current.loadingMore || !current.canLoadMore) return
        val nextPage = current.page + 1
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingMore = true, error = null) }
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
                            canLoadMore = response.items.size >= PageSize,
                            page = nextPage,
                        )
                    }
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
                        it.copy(loadingMore = false, error = error.message ?: "加载更多失败")
                    }
                }
        }
    }

    fun selectCategory(category: ContentCategory) {
        // 已选中的分类不再重复搜索，避免焦点自动切换时反复触发无用请求
        if (category.id == mutableState.value.selectedCategoryId) return
        search(query = "", categoryId = category.id)
    }

    fun openDetails(video: VideoSummary) {
        // 修复:快速连点两条 video 时,后到的请求要把前一条取消,
        // 否则可能出现"先看到 B 详情、再被 A 详情覆盖"的乱序。
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.details(video) }
                .onSuccess { details ->
                    mutableState.update { it.copy(selected = details, loading = false) }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(loading = false, error = error.message ?: "获取详情失败")
                    }
                }
        }
    }

    fun play(stream: PlaybackOption) {
        val current = mutableState.value
        val selected = current.selected
        val video = current.playingVideo
            ?: selected?.let { sel -> current.items.find { it.id == sel.id } }
        if (selected == null) {
            mutableState.update { state ->
                state.copy(playing = stream, playingVideo = video ?: state.playingVideo)
            }
            return
        }
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.playbackOptions(selected.id) }
                .onSuccess { options ->
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
                                selected = state.selected?.copy(playbackOptions = options)
                                    ?: selected.copy(playbackOptions = options),
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

    /**
     * 播放“下一个”:在当前分类列表里找到正在播放视频的下一条,
     * 拉取其详情并以默认(最高)清晰度直接开播。已是最后一条且还有下一页时,
     * 先加载更多,新页到手后自动续播,保证分页边界不会断档。
     */
    fun playNext() {
        val current = mutableState.value
        val items = current.items
        val curId = current.playingVideo?.id ?: current.selected?.id ?: return
        val idx = items.indexOfFirst { it.id == curId }
        if (idx < 0) return
        val nextIndex = idx + 1
        if (nextIndex >= items.size) {
            // 已到当前已加载列表末尾:若还有下一页,记下续播来源 id;新页到手后
            // 在 loadMore.onSuccess 里自动续播(补上分页边界断档)。若此刻已有
            // loadMore(预取)在跑,就复用它,不重复发起。
            if (current.canLoadMore) {
                pendingAutoAdvanceFromId = curId
                if (!current.loadingMore) loadMore()
            }
            return
        }
        pendingAutoAdvanceFromId = null
        playVideoAt(nextIndex, items)
    }

    /**
     * 播放“上一个”:只在当前已加载列表内回退,不跨页反向加载历史页。
     */
    fun playPrevious() {
        val current = mutableState.value
        val items = current.items
        val curId = current.playingVideo?.id ?: current.selected?.id ?: return
        val idx = items.indexOfFirst { it.id == curId }
        if (idx <= 0) return
        pendingAutoAdvanceFromId = null
        playVideoAt(idx - 1, items, prefetchNextPage = false)
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
        val nextVideo = items[index]
        // 临近列表末尾时顺便预取下一页,保证“下一个”能持续可用
        if (prefetchNextPage && index >= items.size - 3) loadMore()
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.details(nextVideo) }
                .onSuccess { details ->
                    val option = details.playbackOptions.firstOrNull()
                    if (option == null) {
                        mutableState.update {
                            it.copy(
                                loading = false,
                                selected = details,
                                playingVideo = nextVideo,
                                playing = null,
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
                            )
                        }
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            loading = false,
                            playing = null,
                            error = error.message ?: "播放下一个失败",
                        )
                    }
                }
        }
    }

    fun retry() {
        // 当 categories 为空时,意味着首次加载也失败了,需要从分类开始重试;
        // 否则仅重试当前 search,避免覆盖已经加载好的分类数据。
        if (mutableState.value.categories.isEmpty()) {
            loadCategoriesAndSearch()
        } else {
            search(mutableState.value.query)
        }
    }

    fun back() {
        // 取消可能"迟到回写"的任务,否则:在播放页按 ↓(下一个)后立刻按 BACK,
        // back() 把 playing 清空(回到详情),但仍在跑的 detailsJob 完成后又把
        // playing 重新写成非空 → 用户被强行拽回播放页。这与"BACK 漏拦截"同类:
        // BACK 的效果被异步写默默撤销。同理清掉待续播标记,避免 loadMore 完成后
        // 触发自动续播再次跳回播放页。
        detailsJob?.cancel()
        pendingAutoAdvanceFromId = null
        mutableState.update {
            when {
                it.playing != null -> it.copy(playing = null)
                it.selected != null -> it.copy(selected = null)
                else -> it
            }
        }
    }

    private fun loadCategoriesAndSearch() {
        viewModelScope.launch {
            // categories 失败时直接 setError 退出,**不再**调 search()。
            // 避免:网络持续不通 → categories 失败 + search(query="", null) 也 400 失败 → 用户陷入"重试 → 失败 → 重试"的死循环。
            mutableState.update { it.copy(loading = true, error = null) }
            val categoriesResult = runCatchingCancellable { repository.categories() }
            val categories = categoriesResult.getOrNull()
            if (categories == null) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = categoriesResult.exceptionOrNull()?.message ?: "加载分类失败",
                    )
                }
                return@launch
            }
            // categories 拿到了,确定默认选中:
            //   - 若之前已选 ID 仍存在,沿用(用户切了 chip 又触发 retry 不会被重置)
            //   - 否则落到列表首项
            mutableState.update { current ->
                val validId = current.selectedCategoryId
                    ?.takeIf { id -> categories.any { it.id == id } }
                val nextId = validId ?: categories.firstOrNull()?.id
                current.copy(categories = categories, selectedCategoryId = nextId)
            }
            search(mutableState.value.query)
        }
    }
}

private const val PageSize = 18

class MediaViewModelFactory(
    private val repository: MediaRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MediaViewModel(repository) as T
    }
}
