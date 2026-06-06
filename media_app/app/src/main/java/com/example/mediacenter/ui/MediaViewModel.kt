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

    init {
        loadCategoriesAndSearch()
    }

    fun search(query: String, categoryId: String? = mutableState.value.selectedCategoryId) {
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
                }
                .onFailure { error ->
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
        mutableState.update { state ->
            val video = state.playingVideo
                ?: state.selected?.let { sel -> state.items.find { it.id == sel.id } }
            state.copy(playing = stream, playingVideo = video ?: state.playingVideo)
        }
    }

    /**
     * 播放“下一个”:在当前分类列表里找到正在播放视频的下一条,
     * 拉取其详情并以默认(最高)清晰度直接开播。已是最后一条时尝试加载更多。
     */
    fun playNext() {
        val current = mutableState.value
        val items = current.items
        val curId = current.playingVideo?.id ?: current.selected?.id ?: return
        val idx = items.indexOfFirst { it.id == curId }
        if (idx < 0) return
        val nextIndex = idx + 1
        if (nextIndex >= items.size) {
            // 已到当前已加载列表末尾:触发加载更多,用户可稍后再次按下一个
            loadMore()
            return
        }
        val nextVideo = items[nextIndex]
        // 临近列表末尾时顺便预取下一页,保证“下一个”能持续可用
        if (nextIndex >= items.size - 3) loadMore()
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.details(nextVideo) }
                .onSuccess { details ->
                    val option = details.playbackOptions.firstOrNull()
                    if (option == null) {
                        mutableState.update {
                            it.copy(loading = false, error = "该视频暂无可播放清晰度")
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
                        it.copy(loading = false, error = error.message ?: "播放下一个失败")
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
            search(mutableState.value.query, mutableState.value.selectedCategoryId)
        }
    }

    fun back() {
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
            search(mutableState.value.query, mutableState.value.selectedCategoryId)
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
