package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.data.friendlyLoadMessage
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页顶部的「继续观看」伪分类 id；不会与真实目录分类冲突。 */
const val HomeContinueTabId = "__continue__"

data class HomeUiState(
    val categories: List<ContentCategory> = emptyList(),
    // 当前只展示一个分类：真实分类 id 或 HomeContinueTabId。
    val selectedTab: String = HomeContinueTabId,
    val items: List<VideoSummary> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val canLoadMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val error: String? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val currentProvider: String = "",
    // 本地观看历史(ViewModel 在 IO 线程读取,UI 只负责渲染)
    val watchEntries: List<WatchEntry> = emptyList(),
)

/**
 * 首页数据：一次只加载一个分类，分类标签切换时按需拉取并缓存。
 *
 * 不预取全部分类的理由：YouTube 分类是服务端实时 ytsearch，一次拉 6 个分类
 * 会让首页等待成倍增加，也会无谓占用解析服务并发。缓存 10 分钟，来回切分类
 * 仍然秒开。
 */
class HomeViewModel(
    private val repository: MediaRepositoryApi = MediaRepositoryHolder.repository,
    // 单测关闭进程级更新检查，避免依赖 Application/网络。
    private val autoCheckUpdate: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val updateState: StateFlow<UpdateDialogState?> = AppUpdateHolder.updateState

    private var initialFocusHandled = false

    fun consumeInitialFocus(): Boolean {
        if (initialFocusHandled) return false
        initialFocusHandled = true
        return true
    }

    private var loadHomeJob: Job? = null
    private var contentJob: Job? = null
    private var loadMoreJob: Job? = null

    private data class CategoryCacheEntry(
        val provider: String,
        val items: List<VideoSummary>,
        val page: Int,
        val total: Int,
        val canLoadMore: Boolean,
        val timestamp: Long,
    )

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, CategoryCacheEntry>()

    init {
        loadHome()
        refreshWatchHistory()
        if (autoCheckUpdate) AppUpdateHolder.requestCheck()
    }

    fun refreshWatchHistory() {
        val context = MediaRepositoryHolder.applicationContext ?: return
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { WatchHistory.entries(context) }
            _uiState.update { it.copy(watchEntries = entries) }
            // 继续观看被清空时不要让用户停在空白伪分类上，回到第一个真实分类。
            val state = _uiState.value
            if (entries.isEmpty() && state.selectedTab == HomeContinueTabId &&
                state.categories.isNotEmpty() && !state.isLoading
            ) {
                selectTab(state.categories.first().id)
            }
        }
    }

    /** 继续观看直接播放前登记历史（IO 线程）。 */
    fun registerPlayed(video: VideoSummary, page: Int) {
        val context = MediaRepositoryHolder.applicationContext ?: return
        val provider = repository.currentProvider
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                WatchHistory.upsert(
                    context,
                    WatchEntry(
                        videoId = video.id,
                        provider = provider,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl.orEmpty(),
                        durationSeconds = video.durationSeconds ?: 0L,
                        page = page,
                    ),
                )
            }
            refreshWatchHistory()
        }
    }

    fun loadHome() {
        viewModelScope.launch { repository.refreshCredentials() }
        loadHomeJob?.cancel()
        contentJob?.cancel()
        loadMoreJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val providers = repository.providers()
                _uiState.update {
                    it.copy(providers = providers, currentProvider = repository.currentProvider)
                }
                val categories = repository.categories()
                val currentTab = _uiState.value.selectedTab
                val target = when {
                    categories.any { it.id == currentTab } -> currentTab
                    categories.isNotEmpty() -> categories.first().id
                    else -> HomeContinueTabId
                }
                _uiState.update {
                    it.copy(
                        categories = categories,
                        selectedTab = target,
                        isLoading = false,
                    )
                }
                if (target != HomeContinueTabId) {
                    loadCategory(target, force = true)
                } else {
                    _uiState.update {
                        it.copy(
                            items = emptyList(),
                            page = 1,
                            total = 0,
                            canLoadMore = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.e("Home", "loadHome failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.friendlyLoadMessage("首页加载失败，请重试"),
                    )
                }
            }
        }
    }

    fun selectTab(tab: String) {
        val state = _uiState.value
        if (tab == state.selectedTab &&
            (tab == HomeContinueTabId || state.items.isNotEmpty() || state.isLoading)
        ) {
            return
        }
        if (tab == HomeContinueTabId) {
            contentJob?.cancel()
            loadMoreJob?.cancel()
            _uiState.update {
                it.copy(
                    selectedTab = tab,
                    items = emptyList(),
                    page = 1,
                    total = 0,
                    canLoadMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    loadMoreError = null,
                    error = null,
                )
            }
            return
        }
        loadCategory(tab, force = false)
    }

    private fun loadCategory(categoryId: String, force: Boolean) {
        val provider = repository.currentProvider
        if (!force) {
            val cached = categoryCache[categoryId]
            if (cached != null && cached.provider == provider &&
                System.currentTimeMillis() - cached.timestamp < CategoryCacheTtlMs
            ) {
                contentJob?.cancel()
                loadMoreJob?.cancel()
                _uiState.update {
                    it.copy(
                        selectedTab = categoryId,
                        items = cached.items,
                        page = cached.page,
                        total = cached.total,
                        canLoadMore = cached.canLoadMore,
                        isLoading = false,
                        isLoadingMore = false,
                        loadMoreError = null,
                        error = null,
                    )
                }
                return
            }
        }
        contentJob?.cancel()
        loadMoreJob?.cancel()
        _uiState.update {
            it.copy(
                selectedTab = categoryId,
                items = emptyList(),
                page = 1,
                total = 0,
                canLoadMore = false,
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = null,
                error = null,
            )
        }
        contentJob = viewModelScope.launch {
            try {
                val response = repository.catalogPage(categoryId, page = 1, pageSize = HomePageSize)
                if (_uiState.value.selectedTab != categoryId ||
                    repository.currentProvider != provider
                ) return@launch
                val items = response.items.orEmpty()
                val canLoadMore = canLoadMore(response.total, items.size)
                categoryCache[categoryId] = CategoryCacheEntry(
                    provider = provider,
                    items = items,
                    page = response.page.coerceAtLeast(1),
                    total = response.total,
                    canLoadMore = canLoadMore,
                    timestamp = System.currentTimeMillis(),
                )
                trimCache()
                _uiState.update {
                    it.copy(
                        items = items,
                        page = response.page.coerceAtLeast(1),
                        total = response.total,
                        canLoadMore = canLoadMore,
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Home", "load category $categoryId failed: ${e.message}", e)
                if (_uiState.value.selectedTab == categoryId) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.friendlyLoadMessage("分类内容加载失败，请重试"),
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val categoryId = state.selectedTab
        if (categoryId == HomeContinueTabId) return
        if (repository.currentProvider == "youtube") return
        if (state.isLoading || state.isLoadingMore || state.loadMoreError != null || !state.canLoadMore) return
        val provider = repository.currentProvider
        val nextPage = state.page + 1
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                val response = repository.catalogPage(categoryId, page = nextPage, pageSize = HomePageSize)
                if (_uiState.value.selectedTab != categoryId ||
                    repository.currentProvider != provider
                ) return@launch
                val merged = (_uiState.value.items + response.items.orEmpty()).distinctBy { it.id }
                val canLoadMore = canLoadMore(response.total, merged.size)
                _uiState.update {
                    it.copy(
                        items = merged,
                        page = response.page.coerceAtLeast(nextPage),
                        total = response.total,
                        canLoadMore = canLoadMore,
                        isLoadingMore = false,
                    )
                }
                categoryCache[categoryId] = CategoryCacheEntry(
                    provider = provider,
                    items = merged,
                    page = response.page.coerceAtLeast(nextPage),
                    total = response.total,
                    canLoadMore = canLoadMore,
                    timestamp = System.currentTimeMillis(),
                )
                trimCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Home", "load more $categoryId failed: ${e.message}", e)
                if (_uiState.value.selectedTab == categoryId) {
                    _uiState.update {
                        it.copy(isLoadingMore = false, loadMoreError = "加载更多失败，请重试")
                    }
                }
            }
        }
    }

    fun retryLoadMore() {
        _uiState.update { it.copy(loadMoreError = null) }
        loadMore()
    }

    fun retry() {
        val state = _uiState.value
        when {
            state.categories.isEmpty() -> loadHome()
            state.selectedTab == HomeContinueTabId -> refreshWatchHistory()
            else -> loadCategory(state.selectedTab, force = true)
        }
    }

    private fun canLoadMore(total: Int, loaded: Int): Boolean {
        if (repository.currentProvider == "youtube") return false
        if (loaded <= 0) return false
        return if (total > 0) loaded < total else loaded >= HomePageSize
    }

    private fun trimCache() {
        while (categoryCache.size > MaxCategoryCacheEntries) {
            categoryCache.keys.firstOrNull()?.let(categoryCache::remove)
        }
    }

    fun selectProvider(provider: String) {
        repository.setProvider(provider)
        categoryCache.clear()
        contentJob?.cancel()
        loadMoreJob?.cancel()
        _uiState.update {
            it.copy(
                categories = emptyList(),
                selectedTab = HomeContinueTabId,
                items = emptyList(),
                page = 1,
                total = 0,
                canLoadMore = false,
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = null,
                error = null,
            )
        }
        loadHome()
    }

    private companion object {
        const val HomePageSize = 18
        const val CategoryCacheTtlMs = 10 * 60 * 1000L
        const val MaxCategoryCacheEntries = 24
    }
}

class HomeViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel() as T
    }
}
