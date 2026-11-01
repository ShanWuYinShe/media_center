package com.example.mediacenter.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.friendlyLoadMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryVideosUiState(
    val categoryId: String = "",
    val title: String = "",
    val items: List<VideoSummary> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val filter: DurationFilter = DurationFilter.ALL,
    val isLoadingInitial: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreFailed: Boolean = false,
    // 没有更多数据(服务端 total 缺失/忽略 page 时也可靠终止翻页)
    val noMore: Boolean = false,
)

/**
 * 分类"查看全部"分页浏览:每次滚动到列表底部加载下一页,已加载内容跨翻页合并去重。
 * server 只提供 approved 目录分页数据,不做实时搜索,因此纯分页即可。
 *
 * 时长筛选由服务端完成(不是过滤已加载页):切换档位会取消在途请求、重置列表
 * 并从第 1 页重新拉取,保证 total 与内容都只属于当前档位。
 */
class CategoryVideosViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryVideosUiState())
    val uiState: StateFlow<CategoryVideosUiState> = _uiState.asStateFlow()

    // 同一时刻只允许一个列表请求在途:切档/重载会取消旧的翻页请求,
    // 避免旧档位的响应晚到后混进新列表。
    private var requestJob: Job? = null

    fun load(categoryId: String, title: String) {
        val current = _uiState.value
        // 同一分类同一档位已加载过(如重组触发)则不重复请求
        if (current.categoryId == categoryId && !current.isLoadingInitial && current.items.isNotEmpty()) {
            return
        }
        fetchFirstPage(categoryId, title, current.filter)
    }

    /** 切换时长档位:重置列表并重新从第 1 页拉取(服务端过滤)。 */
    fun selectFilter(filter: DurationFilter) {
        val current = _uiState.value
        if (current.filter == filter) return
        // 首帧尚未拿到路由参数时点筛选:没有分类 id 可查,等 LaunchedEffect 加载
        if (current.categoryId.isEmpty()) return
        fetchFirstPage(current.categoryId, current.title, filter)
    }

    private fun fetchFirstPage(categoryId: String, title: String, filter: DurationFilter) {
        requestJob?.cancel()
        _uiState.update {
            it.copy(
                categoryId = categoryId,
                title = title,
                filter = filter,
                items = emptyList(),
                page = 1,
                total = 0,
                isLoadingInitial = true,
                isLoadingMore = false,
                error = null,
                loadMoreFailed = false,
                // 必须重置:同一 VM 换分类/换档位时,旧的 noMore=true 会永久挡住 loadMore
                noMore = false,
            )
        }
        requestJob = viewModelScope.launch {
            try {
                val response = repository.catalogPage(
                    categoryId,
                    page = 1,
                    minDurationSeconds = filter.minSeconds,
                    maxDurationSeconds = filter.maxSeconds,
                )
                // 请求期间用户可能又切了档位:只接受与当前状态匹配的结果。
                _uiState.update { state ->
                    if (state.categoryId != categoryId || state.filter != filter) return@update state
                    state.copy(
                        items = response.items.orEmpty(),
                        page = response.page.coerceAtLeast(1),
                        total = response.total,
                        isLoadingInitial = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (state.categoryId != categoryId || state.filter != filter) return@update state
                    state.copy(isLoadingInitial = false, error = e.friendlyLoadMessage("分类内容加载失败，请重试"))
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingInitial || state.isLoadingMore || state.loadMoreFailed || state.noMore) return
        if (state.total > 0 && state.items.size >= state.total) return
        val filter = state.filter
        val categoryId = state.categoryId
        val nextPage = state.page + 1
        _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        requestJob = viewModelScope.launch {
            try {
                val response = repository.catalogPage(
                    categoryId,
                    page = nextPage,
                    minDurationSeconds = filter.minSeconds,
                    maxDurationSeconds = filter.maxSeconds,
                )
                _uiState.update { current ->
                    if (current.categoryId != categoryId || current.filter != filter) return@update current
                    val merged = (current.items + response.items.orEmpty()).distinctBy { it.id }
                    current.copy(
                        items = merged,
                        page = response.page.coerceAtLeast(nextPage),
                        total = response.total,
                        isLoadingMore = false,
                        // 终止保护:服务端 total 缺失(=0)或忽略 page 参数时,
                        // 空页/无新增即判定没有更多,避免滚到底无限发请求。
                        noMore = response.items.isNullOrEmpty() ||
                            (response.total > 0 && merged.size >= response.total),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { current ->
                    if (current.categoryId != categoryId || current.filter != filter) return@update current
                    // 翻页失败不丢已有列表,滚动到页底时展示重试入口
                    current.copy(isLoadingMore = false, loadMoreFailed = true)
                }
            }
        }
    }

    fun retryLoadMore() {
        _uiState.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }
}
