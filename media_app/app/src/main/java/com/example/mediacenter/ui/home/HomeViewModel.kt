package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.HomeSnapshot
import com.example.mediacenter.data.HomeSnapshotStore
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.data.friendlyLoadMessage
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.util.DevicePerformance
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
    // 阶段 0 基线:从 ViewModel 创建到首屏内容就绪的耗时(含快照恢复/网络两条路径)。
    private val createdAtMs = System.currentTimeMillis()

    private data class CategoryCacheEntry(
        val provider: String,
        val items: List<VideoSummary>,
        val page: Int,
        val total: Int,
        val canLoadMore: Boolean,
        val timestamp: Long,
    )

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, CategoryCacheEntry>()

    // 相邻分类预热:用户左右切类时目标分类往往已在后台拉好,切过去直接命中缓存。
    // 只预热 bilibili 目录(youtube 分类是实时 ytsearch,不能替用户提前消耗配额),
    // 且每个分类只发一次、失败静默。
    private var categoryPrefetchJob: Job? = null
    private val prefetchInFlight = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        restoreSnapshotThenLoad()
        refreshWatchHistory()
        if (autoCheckUpdate) AppUpdateHolder.requestCheck()
    }

    /**
     * 首屏 SWR:先渲染 10 分钟内的同 provider 快照(缓存内容秒出),再后台刷新。
     * 刷新期间保留旧内容;刷新失败只记日志,不用全屏错误盖掉已有内容。
     * 测试/无 Application context 时直接走网络(行为与旧实现一致)。
     */
    private fun restoreSnapshotThenLoad() {
        val context = MediaRepositoryHolder.applicationContext
        if (context == null) {
            loadHome()
            return
        }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { HomeSnapshotStore.load(context) }
            val restored = snapshot?.takeIf { it.provider == repository.currentProvider }
            if (restored != null) {
                categoryCache[restored.selectedTab] = CategoryCacheEntry(
                    provider = restored.provider,
                    items = restored.items,
                    page = 1,
                    total = restored.total,
                    canLoadMore = canLoadMore(restored.total, restored.items.size),
                    timestamp = System.currentTimeMillis(),
                )
                _uiState.update {
                    it.copy(
                        categories = restored.categories,
                        selectedTab = restored.selectedTab,
                        items = restored.items,
                        page = 1,
                        total = restored.total,
                        canLoadMore = canLoadMore(restored.total, restored.items.size),
                        isLoading = false,
                        isLoadingMore = false,
                        loadMoreError = null,
                        error = null,
                        currentProvider = restored.provider,
                    )
                }
                AppLog.i(
                    "Perf",
                    "home snapshot restored in ${System.currentTimeMillis() - createdAtMs}ms " +
                        "(tab=${restored.selectedTab}, items=${restored.items.size})",
                )
            }
            loadHome(keepContent = restored != null)
        }
    }

    /** 首次页加载成功后写快照:只存当前分类第一页,容量与 embed 成本都可控。 */
    private fun persistSnapshot() {
        val context = MediaRepositoryHolder.applicationContext ?: return
        val state = _uiState.value
        if (state.selectedTab == HomeContinueTabId) return
        if (state.categories.isEmpty() || state.items.isEmpty()) return
        val snapshot = HomeSnapshot(
            provider = repository.currentProvider,
            categories = state.categories,
            selectedTab = state.selectedTab,
            // 低端盒子少存一些首屏快照:减少 JSON 编解码与内存占用。
            items = state.items.take(if (DevicePerformance.isLowEnd) 12 else HomePageSize),
            total = state.total,
            savedAtEpochMs = System.currentTimeMillis(),
        )
        viewModelScope.launch(Dispatchers.IO) { HomeSnapshotStore.save(context, snapshot) }
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

    fun loadHome(keepContent: Boolean = false) {
        viewModelScope.launch { repository.refreshCredentials() }
        loadHomeJob?.cancel()
        contentJob?.cancel()
        loadMoreJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            if (keepContent) {
                // SWR 刷新:保留已渲染快照,不显示骨架/转圈。
                _uiState.update { it.copy(error = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                // 一次 bootstrap 聚合请求拿到 providers + 分类(替代串行两次 RTT)。
                val (providers, categories) = repository.bootstrap()
                _uiState.update {
                    it.copy(providers = providers, currentProvider = repository.currentProvider)
                }
                val currentTab = _uiState.value.selectedTab
                val target = when {
                    categories.any { it.id == currentTab } -> currentTab
                    categories.isNotEmpty() -> categories.first().id
                    else -> HomeContinueTabId
                }
                val targetChanged = target != currentTab
                _uiState.update {
                    it.copy(
                        categories = categories,
                        selectedTab = target,
                        isLoading = false,
                        // 快照的分类已不在服务端目录:旧分类内容不能挂到新分类上。
                        items = if (targetChanged) emptyList() else it.items,
                        page = if (targetChanged) 1 else it.page,
                        total = if (targetChanged) 0 else it.total,
                        canLoadMore = if (targetChanged) false else it.canLoadMore,
                    )
                }
                if (target != HomeContinueTabId) {
                    loadCategory(target, force = true, keepContent = keepContent)
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
                // SWR 刷新失败且已有快照内容:保留内容,不弹全屏错误(用户仍能看/点)。
                val hasContent = keepContent && _uiState.value.items.isNotEmpty()
                _uiState.update {
                    if (hasContent) {
                        it.copy(isLoading = false)
                    } else {
                        it.copy(
                            isLoading = false,
                            error = e.friendlyLoadMessage("首页加载失败，请重试"),
                        )
                    }
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

    private fun loadCategory(categoryId: String, force: Boolean, keepContent: Boolean = false) {
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
                AppLog.i(
                    "Perf",
                    "home first page ready in ${System.currentTimeMillis() - createdAtMs}ms " +
                        "(tab=$categoryId, cache=hit)",
                )
                prefetchAdjacentCategories()
                return
            }
        }
        contentJob?.cancel()
        loadMoreJob?.cancel()
        // SWR 刷新同一分类:保留已渲染内容,不闪骨架。
        val keepShownItems = keepContent &&
            _uiState.value.selectedTab == categoryId &&
            _uiState.value.items.isNotEmpty()
        // 过期缓存(超出 TTL 但仍在内存):先展示旧内容再后台刷新,避免
        // "切回刚才看过的分类"闪一下骨架;刷新失败也保留旧内容。
        val staleCache = if (!keepContent) {
            categoryCache[categoryId]?.takeIf { it.provider == provider }
        } else {
            null
        }
        if (keepShownItems) {
            _uiState.update {
                it.copy(
                    selectedTab = categoryId,
                    isLoading = false,
                    isLoadingMore = false,
                    loadMoreError = null,
                    error = null,
                )
            }
        } else if (staleCache != null) {
            // 过期但不是空的分类:立即渲染旧内容,后台刷新到达后原地替换。
            _uiState.update {
                it.copy(
                    selectedTab = categoryId,
                    items = staleCache.items,
                    page = staleCache.page,
                    total = staleCache.total,
                    canLoadMore = staleCache.canLoadMore,
                    // 内容已在屏上,isLoading 只用于拦住期间的 loadMore 抢跑
                    // (后台刷新会整页替换 items,不能同时追加旧页)。
                    isLoading = true,
                    isLoadingMore = false,
                    loadMoreError = null,
                    error = null,
                )
            }
            AppLog.i(
                "Perf",
                "home category $categoryId: stale cache shown " +
                    "(${staleCache.items.size} items), refreshing",
            )
        } else {
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
        }
        contentJob = viewModelScope.launch {
            try {
                // 相邻分类预热可能已在路上:等它填缓存,命中就省一次完全相同的请求。
                if (!force) {
                    prefetchInFlight[categoryId]?.join()
                    val preloaded = categoryCache[categoryId]?.takeIf {
                        it.provider == provider &&
                            System.currentTimeMillis() - it.timestamp < CategoryCacheTtlMs
                    }
                    if (preloaded != null &&
                        _uiState.value.selectedTab == categoryId &&
                        repository.currentProvider == provider
                    ) {
                        _uiState.update {
                            it.copy(
                                items = preloaded.items,
                                page = preloaded.page,
                                total = preloaded.total,
                                canLoadMore = preloaded.canLoadMore,
                                isLoading = false,
                            )
                        }
                        AppLog.i(
                            "Perf",
                            "home first page ready in ${System.currentTimeMillis() - createdAtMs}ms " +
                                "(tab=$categoryId, cache=hit)",
                        )
                        prefetchAdjacentCategories()
                        return@launch
                    }
                }
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
                AppLog.i(
                    "Perf",
                    "home first page ready in ${System.currentTimeMillis() - createdAtMs}ms " +
                        "(tab=$categoryId, cache=miss)",
                )
                persistSnapshot()
                prefetchAdjacentCategories()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Home", "load category $categoryId failed: ${e.message}", e)
                if (_uiState.value.selectedTab == categoryId) {
                    // SWR/过期缓存刷新失败且已有内容:保留旧内容,只记日志。
                    val hasContent = (keepContent || staleCache != null) &&
                        _uiState.value.items.isNotEmpty()
                    _uiState.update {
                        if (hasContent) {
                            it.copy(isLoading = false)
                        } else {
                            it.copy(
                                isLoading = false,
                                error = e.friendlyLoadMessage("分类内容加载失败，请重试"),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 相邻分类预热:当前分类加载完成后,顺带把左右相邻分类的第一页拉进缓存。
     *
     * TV 遥控器换类是"焦点落到标签即切"的高频操作;提前预热邻居后,左右键切类
     * 直接命中 10 分钟缓存,不再出现骨架等待。只预热目录型 provider(bilibili),
     * youtube 分类是实时 ytsearch 不能替用户提前消耗配额;失败静默,不影响首页。
     */
    private fun prefetchAdjacentCategories() {
        if (repository.currentProvider == "youtube") return
        val state = _uiState.value
        if (state.categories.isEmpty() || state.isLoading) return
        val ids = state.categories.map { it.id }
        val currentIndex = ids.indexOf(state.selectedTab)
        if (currentIndex < 0) return
        val candidates = listOfNotNull(
            ids.getOrNull(currentIndex - 1),
            ids.getOrNull(currentIndex + 1),
        ).filter { it != state.selectedTab }
            .filter { categoryId ->
                val cached = categoryCache[categoryId]
                cached == null || cached.provider != repository.currentProvider ||
                    System.currentTimeMillis() - cached.timestamp >= CategoryCacheTtlMs
            }
            .filter { prefetchInFlight[it] == null }
        if (candidates.isEmpty()) return
        categoryPrefetchJob?.cancel()
        categoryPrefetchJob = viewModelScope.launch {
            val provider = repository.currentProvider
            for (categoryId in candidates) {
                if (repository.currentProvider != provider) return@launch
                val job = viewModelScope.launch { prefetchCategory(categoryId, provider) }
                prefetchInFlight[categoryId] = job
                try {
                    job.join()
                } finally {
                    // 父任务被取消(切源/再次切类)时也要清理,否则该分类会被
                    // 永久标记为"预热在途",之后再也预热不到它。
                    prefetchInFlight.remove(categoryId)
                }
            }
        }
    }

    private suspend fun prefetchCategory(categoryId: String, provider: String) {
        val startedAtMs = System.currentTimeMillis()
        try {
            val response = repository.catalogPage(categoryId, page = 1, pageSize = HomePageSize)
            if (repository.currentProvider != provider) return
            val items = response.items.orEmpty()
            categoryCache[categoryId] = CategoryCacheEntry(
                provider = provider,
                items = items,
                page = response.page.coerceAtLeast(1),
                total = response.total,
                canLoadMore = canLoadMore(response.total, items.size),
                timestamp = System.currentTimeMillis(),
            )
            trimCache()
            AppLog.i(
                "Perf",
                "home category preloaded tab=$categoryId in " +
                    "${System.currentTimeMillis() - startedAtMs}ms (items=${items.size})",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Home", "prefetch category $categoryId failed: ${e.message}", e)
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
        val startedAtMs = System.currentTimeMillis()
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
                persistSnapshot()
                AppLog.i(
                    "Perf",
                    "home load more tab=$categoryId page=${response.page.coerceAtLeast(nextPage)} " +
                        "in ${System.currentTimeMillis() - startedAtMs}ms (items=${merged.size})",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(
                    "Home",
                    "load more $categoryId failed: ${e.message} " +
                        "(after ${System.currentTimeMillis() - startedAtMs}ms)",
                    e,
                )
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

    /**
     * 仅测试用:注入一条指定年龄的分类缓存。
     * 用于验证"过期缓存先展示、后台刷新"的 SWR 行为(真实 TTL 10 分钟无法在单测里等待)。
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedCategoryCacheForTest(
        categoryId: String,
        items: List<VideoSummary>,
        ageMs: Long,
    ) {
        categoryCache[categoryId] = CategoryCacheEntry(
            provider = repository.currentProvider,
            items = items,
            page = 1,
            total = items.size,
            canLoadMore = false,
            timestamp = System.currentTimeMillis() - ageMs,
        )
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
        categoryPrefetchJob?.cancel()
        prefetchInFlight.clear()
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
