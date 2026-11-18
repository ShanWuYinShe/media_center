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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
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
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: MediaRepositoryApi = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // 输入防抖:遥控器/键盘连按时只触发一次搜索。
    private val queryFlow = MutableStateFlow("")

    // 翻页请求:新关键词/新搜索开始时取消,避免旧页结果追加到新查询。
    private var loadMoreJob: Job? = null
    // 同一关键词的显式重跑(错误重试/重复点热词):queryFlow 对相同值不发射,只能直接触发。
    private var immediateSearchJob: Job? = null

    init {
        // 最近搜索从磁盘读:不能阻塞构造/首帧,放到 IO。
        viewModelScope.launch { loadHistory() }
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
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

    fun search(query: String) {
        // 清空/过短时立即清结果,不等防抖。
        if (query.trim().length < 2) {
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
            queryFlow.value = query
            return
        }
        val sameQuery = query == _uiState.value.query
        // 立即进入加载态:600ms 防抖窗口内 UI 不会把旧结果/空结果误判为
        // "没有找到相关视频"(此前会闪一下错误结论)。
        _uiState.value = _uiState.value.copy(query = query, isSearching = true, error = null)
        if (sameQuery) {
            // StateFlow 对相同值不发射,queryFlow 不会触发防抖搜索:
            // 显式重跑一次,修复"失败后点重试无反应 / 重复点同一个热词无反应"。
            immediateSearchJob?.cancel()
            immediateSearchJob = viewModelScope.launch { performSearch(query) }
        } else {
            // 新关键词:取消可能还在跑的"同词重试",避免旧结果覆盖新查询。
            immediateSearchJob?.cancel()
            queryFlow.value = query
        }
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
                val response = repository.search(query, nextPage, SEARCH_PAGE_SIZE)
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
        const val SEARCH_DEBOUNCE_MS = 600L
        // 与服务端 pageSize 上限(40)留余量,兼顾首屏速度。
        const val SEARCH_PAGE_SIZE = 30
        // 目录搜索(本地解析 provider)总预算;YouTube 实时搜索有服务端 35s 预算,
        // 不走这个短超时(冷启动 ytsearch 本身就可能十几秒)。
        const val CATALOG_SEARCH_TIMEOUT_MS = 15_000L
    }
}
