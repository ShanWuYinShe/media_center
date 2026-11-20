package com.example.mediacenter.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ApiException
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.SearchHistory
import com.example.mediacenter.data.SearchResponse
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.friendlyLoadMessage
import com.example.mediacenter.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 目录搜索超出总预算(见 [CATALOG_SEARCH_TIMEOUT_MS])。 */
private class SearchTimeoutException : Exception("search timeout")

data class SearchUiState(
    val query: String = "",
    val results: List<VideoSummary> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val page: Int = 1,
    val total: Int = 0,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    // 服务端目录搜索可分页;youtube 实时搜索是单页
    val paged: Boolean = false,
    val noMore: Boolean = true,
    // 最近一次完成(成功或失败)的关键词;UI 用它区分"仍在防抖/请求"与
    // "已返回但零结果",避免热词加载态无法结束。
    val completedQuery: String? = null,
    // 本地最近搜索:空态优先展示,一键重搜(遥控器输入成本高)。
    val history: List<String> = emptyList(),
)

/**
 * 搜索编排:
 * - youtube provider 走 server 的实时 ytsearch 通道(单页 20 条);
 * - 其他 provider 走 server 的 approved 目录搜索(可分页);
 * - 失败直接给出错误 + 重试,不再维护"本地拉全目录再过滤"的回退缓存
 *   (server 已是目录唯一事实源,旧回退只为兼容无 /api/search 的旧服务端)。
 */
class SearchViewModel(
    private val repository: MediaRepositoryApi = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // 翻页请求:新关键词/新搜索开始时取消,避免旧页结果追加到新查询。
    private var loadMoreJob: Job? = null
    // 显式搜索任务:同一关键词重复提交(重试/再搜)也必须能重新发起请求。
    private var immediateSearchJob: Job? = null

    init {
        // 最近搜索从磁盘读:不能阻塞构造/首帧,放到 IO。
        viewModelScope.launch { loadHistory() }
    }

    private suspend fun loadHistory() {
        val context = MediaRepositoryHolder.applicationContext ?: return
        val entries = withContext(Dispatchers.IO) { SearchHistory.entries(context) }
        _uiState.update { it.copy(history = entries) }
    }

    private suspend fun recordHistory(query: String) {
        if (query.trim().length < 2) return
        val context = MediaRepositoryHolder.applicationContext ?: return
        val entries = withContext(Dispatchers.IO) {
            SearchHistory.record(context, query)
            SearchHistory.entries(context)
        }
        _uiState.update { it.copy(history = entries) }
    }

    /**
     * 输入框文本变化:**只更新文本，不发起搜索**。
     *
     * TV 上与手机不同:边输入边搜会把每一次按键都变成一次请求(遥控器逐字输入
     * 十几键就是十几次请求,既慢又容易撞服务端限流),而且用户还没输完就看到
     * 半截关键词的结果。所以只有显式提交([submit])才真正搜索。
     */
    fun onQueryChange(query: String) {
        if (query == _uiState.value.query) return
        if (query.trim().length < 2) {
            // 清空/过短:直接回到空态，不发请求。
            loadMoreJob?.cancel()
            immediateSearchJob?.cancel()
            _uiState.value = _uiState.value.copy(
                query = query,
                results = emptyList(),
                isSearching = false,
                error = null,
                isLoadingMore = false,
                loadMoreFailed = false,
                paged = false,
                noMore = true,
                total = 0,
                page = 1,
                completedQuery = null,
            )
            return
        }
        // 保留旧结果继续展示(不闪骨架)，等用户按搜索键再替换。
        _uiState.value = _uiState.value.copy(query = query, error = null)
    }

    /**
     * 显式提交搜索:IME 搜索键 / 搜索按钮 / 热词 / 重试都走这里。
     *
     * @param query 为空时用输入框当前文本(搜索按钮与 IME 的常见调用方式)。
     */
    fun submit(query: String = _uiState.value.query) {
        val keyword = query.trim()
        if (keyword.length < 2) {
            // 不足 2 字符:留在空态，不发请求。
            onQueryChange(query)
            return
        }
        if (keyword != _uiState.value.query) {
            _uiState.value = _uiState.value.copy(query = keyword)
        }
        immediateSearchJob?.cancel()
        loadMoreJob?.cancel()
        _uiState.value = _uiState.value.copy(isSearching = true, error = null)
        // 直接起协程而不是走"变化才触发"的流:同一关键词重复提交(重试/再搜)
        // 也必须重新发起请求。
        immediateSearchJob = viewModelScope.launch { performSearch(keyword) }
    }

    /** 清空搜索(输入框清除按钮 / 空态「清空搜索」)。 */
    fun clear() {
        onQueryChange("")
    }

    private suspend fun performSearch(query: String) {
        loadMoreJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }
        _uiState.value = _uiState.value.copy(
            isSearching = true,
            error = null,
            isLoadingMore = false,
            loadMoreFailed = false,
            paged = false,
            noMore = true,
            page = 1,
        )
        val provider = repository.currentProvider
        val startedAtMs = System.currentTimeMillis()
        try {
            if (provider == "youtube") {
                val response = repository.youtubeSearch(query)
                val items = response.items.orEmpty()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    results = items,
                    total = items.size,
                    page = 1,
                    paged = false,
                    noMore = true,
                    completedQuery = query,
                )
                recordHistory(query)
            } else if (provider == "bilibili") {
                // B 站走**全站**实时搜索(服务端代理 B 站官方接口):目录搜索只覆盖
                // 已审核条目，用户搜全站视频会大面积搜不到(线上反馈的核心问题)。
                val response = repository.bilibiliSearch(query, page = 1)
                val items = response.items.orEmpty()
                val noMore = items.size >= response.total || items.isEmpty()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    results = items,
                    total = response.total,
                    page = 1,
                    paged = !noMore,
                    noMore = noMore,
                    completedQuery = query,
                )
                recordHistory(query)
            } else {
                // 目录搜索给总预算:弱网/服务端不可达时 MediaApi 内部重试可达 ~47s,
                // 期间已有旧结果时界面看起来像"搜了没反应"。超时落明确文案。
                // YouTube 实时搜索自身有服务端 35s 预算,不走这里的短超时。
                val response = withTimeoutOrNull(CATALOG_SEARCH_TIMEOUT_MS) {
                    repository.search(query, page = 1, pageSize = SEARCH_PAGE_SIZE)
                } ?: throw SearchTimeoutException()
                applyFirstPage(query, response)
                recordHistory(query)
            }
            AppLog.i(
                "Perf",
                "search \"$query\" provider=$provider in " +
                    "${System.currentTimeMillis() - startedAtMs}ms " +
                    "(count=${_uiState.value.results.size})",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(
                "Search",
                "search failed: ${e.message} " +
                    "(after ${System.currentTimeMillis() - startedAtMs}ms)",
                e,
            )
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                results = emptyList(),
                error = searchErrorMessage(e),
                completedQuery = query,
            )
        }
    }

    private fun applyFirstPage(query: String, response: SearchResponse) {
        val items = response.items.orEmpty()
        val noMore = items.isEmpty() || (response.total > 0 && items.size >= response.total)
        _uiState.value = _uiState.value.copy(
            isSearching = false,
            results = items,
            total = response.total,
            page = 1,
            paged = !noMore,
            noMore = noMore,
            completedQuery = query,
        )
    }

    /** 目录搜索分页:滚动到底时按当前关键词拉下一页。 */
    fun loadMore() {
        val state = _uiState.value
        if (!state.paged || state.isSearching || state.isLoadingMore || state.loadMoreFailed ||
            state.noMore || state.query.trim().length < 2
        ) {
            return
        }
        if (state.total > 0 && state.results.size >= state.total) return
        val query = state.query
        val provider = repository.currentProvider
        val nextPage = state.page + 1
        _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        loadMoreJob = viewModelScope.launch {
            try {
                val response = if (provider == "bilibili") {
                    // B 站全站搜索按页翻(每页 20 条，服务端代理)。
                    repository.bilibiliSearch(query, nextPage)
                } else {
                    repository.search(query, nextPage, SEARCH_PAGE_SIZE)
                }
                _uiState.update { current ->
                    // 期间改了关键词/provider:丢弃旧页。
                    if (current.query != query || repository.currentProvider != provider) {
                        return@update current
                    }
                    val merged = (current.results + response.items.orEmpty()).distinctBy { it.id }
                    val noMore = response.items.isNullOrEmpty() ||
                        (response.total > 0 && merged.size >= response.total)
                    current.copy(
                        results = merged,
                        page = nextPage,
                        total = response.total,
                        isLoadingMore = false,
                        paged = !noMore,
                        noMore = noMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.query == query) {
                    _uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
                }
            }
        }
    }

    fun retryLoadMore() {
        _uiState.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }

    private fun searchErrorMessage(error: Exception): String = when {
        error is ApiException && error.statusCode == 429 -> "搜索过于频繁，请稍后再试"
        error is SearchTimeoutException -> "搜索超时，请检查网络后重试"
        else -> error.friendlyLoadMessage("搜索失败，请重试")
    }

    private companion object {
        // 与服务端 pageSize 上限(40)留余量,兼顾首屏速度。
        const val SEARCH_PAGE_SIZE = 30
        // 目录搜索(本地解析 provider)总预算;YouTube 实时搜索有服务端 35s 预算,
        // 不走这个短超时(冷启动 ytsearch 本身就可能十几秒)。
        const val CATALOG_SEARCH_TIMEOUT_MS = 15_000L
    }
}