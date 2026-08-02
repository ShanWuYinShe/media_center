package com.example.mediacenter.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.log.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<VideoSummary> = emptyList(),
    val categories: List<ContentCategory> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

/**
 * 搜索实现说明:server 只提供目录数据(approved 清单),没有实时搜索接口。
 * 因此这里把当前 provider 各分类的 approved 视频列表**全量拉取到本地缓存**,
 * 再按标题做客户端过滤。首次搜索会先完成目录加载(可能较慢),之后搜索秒回。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SearchViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // 输入防抖:遥控器/键盘连按时只触发一次搜索,避免频繁取消重建请求。
    private val queryFlow = MutableStateFlow("")

    // 本地目录缓存:categoryId -> 该分类全部已拉取视频(仅主线程访问)。
    private val catalogCache = mutableMapOf<String, List<VideoSummary>>()
    // 缓存对应的 provider:provider 切换后目录结构不同,需要重新拉取
    private var cachedProvider: String? = null
    private var catalogLoaded = false

    init {
        viewModelScope.launch { refreshCatalog() }
        viewModelScope.launch {
            queryFlow
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        queryFlow.value = query
    }

    // 并行拉取各分类的前若干页,构建本地过滤缓存。失败时静默降级
    // (保持已有缓存;无缓存时搜索结果为空)。
    private suspend fun refreshCatalog() {
        try {
            repository.refreshCredentials()
            val cats = repository.categories()
            _uiState.value = _uiState.value.copy(categories = cats)
            val loaded = coroutineScope {
                cats.map { cat ->
                    async {
                        val pages = (1..MAX_PAGES_PER_CATEGORY).mapNotNull { page ->
                            val items = repository.catalogPage(cat.id, page).items
                            if (items.isNullOrEmpty()) null else items
                        }
                        cat.id to pages.flatten()
                    }
                }.awaitAll().toMap()
            }
            catalogCache.clear()
            catalogCache.putAll(loaded)
            cachedProvider = repository.currentProvider
            catalogLoaded = true
            AppLog.i("Search", "catalog loaded: ${loaded.values.sumOf { it.size }} videos in ${loaded.size} categories")
        } catch (e: Exception) {
            AppLog.w("Search", "catalog load failed: ${e.message}", e)
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.length < 2) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }
        if (!isCatalogCurrent) {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            refreshCatalog()
            if (!isCatalogCurrent) {
                // 目录加载失败:给出明确错误而非空结果
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    results = emptyList(),
                    error = "目录加载失败，请重试",
                )
                return
            }
        }
        // 同一视频可能出现在多个分类,去重避免列表 key 冲突
        val results = catalogCache.values.flatten()
            .distinctBy { it.id }
            .filter { it.title.contains(query.trim(), ignoreCase = true) }
        _uiState.value = _uiState.value.copy(isSearching = false, results = results, error = null)
    }

    // 目录缓存是否与当前 provider 一致
    private val isCatalogCurrent: Boolean
        get() = cachedProvider == repository.currentProvider && catalogLoaded

    private companion object {
        // 每分类最多拉取的页数,控制本地目录规模(18 条/页)。
        const val MAX_PAGES_PER_CATEGORY = 5
    }
}
