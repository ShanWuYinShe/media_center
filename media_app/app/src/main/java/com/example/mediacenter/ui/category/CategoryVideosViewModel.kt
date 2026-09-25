package com.example.mediacenter.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.CancellationException
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
 */
class CategoryVideosViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryVideosUiState())
    val uiState: StateFlow<CategoryVideosUiState> = _uiState.asStateFlow()

    // 防止并发触发重复翻页/重复初始化的简单互斥
    private var requestInFlight = false

    fun load(categoryId: String, title: String) {
        val current = _uiState.value
        // 同一分类已加载过(如重组触发)则不重复请求
        if (current.categoryId == categoryId && !current.isLoadingInitial && current.items.isNotEmpty()) return
        if (requestInFlight) return
        requestInFlight = true
        _uiState.update {
            it.copy(
                categoryId = categoryId,
                title = title,
                items = emptyList(),
                page = 1,
                total = 0,
                isLoadingInitial = true,
                isLoadingMore = false,
                error = null,
                loadMoreFailed = false,
                // 必须重置:同一 VM 换分类时,旧的 noMore=true 会永久挡住 loadMore
                noMore = false,
            )
        }
        viewModelScope.launch {
            try {
                val response = repository.catalogPage(categoryId, page = 1)
                _uiState.update {
                    it.copy(
                        items = response.items.orEmpty(),
                        page = response.page.coerceAtLeast(1),
                        total = response.total,
                        isLoadingInitial = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingInitial = false, error = "分类内容加载失败，请重试")
                }
            } finally {
                requestInFlight = false
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (requestInFlight || state.isLoadingInitial || state.isLoadingMore || state.loadMoreFailed || state.noMore) return
        if (state.total > 0 && state.items.size >= state.total) return
        requestInFlight = true
        _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        viewModelScope.launch {
            try {
                val nextPage = state.page + 1
                val response = repository.catalogPage(state.categoryId, page = nextPage)
                _uiState.update { current ->
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
                // 翻页失败不丢已有列表,滚动到页底时展示重试入口
                _uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
            } finally {
                requestInFlight = false
            }
        }
    }

    fun retryLoadMore() {
        _uiState.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }
}
