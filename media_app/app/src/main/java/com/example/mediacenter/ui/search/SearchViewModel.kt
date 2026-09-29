package com.example.mediacenter.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val results: List<VideoSummary> = emptyList(),
    val categories: List<ContentCategory> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

/**
 * 搜索实现说明:优先走服务端搜索(bilibili:approved 目录子串匹配;youtube:
 * 服务端 ytsearch 实时搜索),失败时回退——把当前 provider 各分类的 approved 视频列表
 * **拉取到本地缓存**,再按标题做客户端过滤。
 *
 * 惰性策略:首搜只等前 [FAST_PAGES] 页(秒回),剩余页在后台补全。
 * 旧实现一次性拉满 5 页 × 全分类才出结果，冷启动首搜很慢且占内存。
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
    // 是否已完成"足够支撑首搜"的快速加载(前 FAST_PAGES 页)。
    private var catalogReady = false
    // 是否已完成全部页(后台补全)。
    private var catalogComplete = false
    // 补全失败标记:下次搜索前重试,避免本次会话搜索结果永久缺页。
    private var completePending = false
    // 后台补全 job 引用:provider 切换时取消旧补全,防止旧源数据混入新源缓存
    // (refreshCatalogFast 会 clear+putAll,旧补全晚到会覆盖新源内容)。
    private var completeJob: Job? = null

    init {
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

    // 并行拉取某分类的指定页区间，返回扁平化结果。
    private suspend fun loadCategoryPages(catId: String, fromPage: Int, toPage: Int): List<VideoSummary> =
        (fromPage..toPage).mapNotNull { page ->
            val items = repository.catalogPage(catId, page).items
            if (items.isNullOrEmpty()) null else items
        }.flatten()

    // 快速加载:各分类前 FAST_PAGES 页(秒回，足够覆盖首搜常见结果)。
    private suspend fun refreshCatalogFast() {
        try {
            // 新目录接管前取消旧补全:否则旧 provider 的后台补全晚到会把
            // 旧源数据混进新缓存(completeCatalog 合并处另有 provider 校验双保险)。
            completeJob?.cancel()
            repository.refreshCredentials()
            val cats = repository.categories()
            _uiState.value = _uiState.value.copy(categories = cats)
            val loaded = coroutineScope {
                // 并发上限:与 HomeViewModel 同教训,弱网下"分类×页"并发打满
                // 连接池会互相拖慢
                val semaphore = Semaphore(SEARCH_FETCH_CONCURRENCY)
                cats.map { cat ->
                    async {
                        semaphore.withPermit { cat.id to loadCategoryPages(cat.id, 1, FAST_PAGES) }
                    }
                }.awaitAll().toMap()
            }
            catalogCache.clear()
            catalogCache.putAll(loaded)
            cachedProvider = repository.currentProvider
            catalogReady = true
            catalogComplete = false
            AppLog.i("Search", "catalog fast-load: ${loaded.values.sumOf { it.size }} videos in ${loaded.size} categories")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Search", "catalog fast-load failed: ${e.message}", e)
        }
    }

    // 后台补全剩余页(FAST_PAGES+1..MAX)，让搜索结果更全。失败标记 pending,
    // 下次 performSearch 前重试;中断/取消直接向上传播。
    // provider 参数在启动时捕获:合并前校验目录仍属于该 provider,
    // 防止"切 provider 后旧补全晚到"把旧源数据混入新源缓存(竞态修复)。
    private suspend fun completeCatalog(provider: String) {
        val cats = _uiState.value.categories
        if (cats.isEmpty()) return
        try {
            val extra = coroutineScope {
                val semaphore = Semaphore(SEARCH_FETCH_CONCURRENCY)
                cats.map { cat ->
                    async {
                        semaphore.withPermit { cat.id to loadCategoryPages(cat.id, FAST_PAGES + 1, MAX_PAGES_PER_CATEGORY) }
                    }
                }.awaitAll().toMap()
            }
            // 合并前校验:provider 已切换则丢弃本次结果(数据已作废)。
            if (cachedProvider != provider) {
                AppLog.w("Search", "catalog complete dropped: provider switched (${cachedProvider} != $provider)")
                return
            }
            // 合并:已有快速页 + 新增页(去重 by id)
            for ((catId, more) in extra) {
                if (more.isEmpty()) continue
                val existing = catalogCache[catId].orEmpty()
                val merged = (existing + more).distinctBy { it.id }
                catalogCache[catId] = merged
            }
            catalogComplete = true
            completePending = false
            AppLog.i("Search", "catalog complete: ${catalogCache.values.sumOf { it.size }} videos")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败不丢已加载数据;标记 pending,下次搜索前重试
            completePending = true
            AppLog.w("Search", "catalog complete failed (will retry): ${e.message}", e)
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.length < 2) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true, error = null)
        // 远程两级(youtube 实时通道 / server 搜索)任一失败置位:两级全失败且
        // 本地也无结果时,把"网络不可用"与"真无结果"区分开,不再静默回退。
        var remoteFailed = false
        // YouTube 实时搜索:优先调 server 的 YouTube 通道(ytsearch 实时搜索),不查本地
        // catalog(YouTube 内容不要求先入库)。失败仍落到下方本地目录过滤——
        // 至少能搜到已审核入库的 YouTube 内容。
        if (repository.currentProvider == "youtube") {
            val remote = try {
                repository.youtubeSearch(query).items.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remoteFailed = true
                AppLog.w("Search", "youtube search failed, fallback to local catalog: ${e.message}", e)
                null
            }
            if (remote != null) {
                _uiState.value = _uiState.value.copy(isSearching = false, results = remote, error = null)
                return
            }
        }
        // 优先服务端搜索:跨全部分类页、按置顶/分数排序,一次请求出全量结果。
        // 旧版 server 没有 /api/search 或瞬时网络失败时回退本地目录过滤。
        val serverResults = try {
            repository.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remoteFailed = true
            AppLog.w("Search", "server search failed, fallback to local catalog: ${e.message}", e)
            null
        }
        if (serverResults != null) {
            _uiState.value = _uiState.value.copy(isSearching = false, results = serverResults, error = null)
            return
        }
        // ── 本地回退:拉目录缓存后按标题过滤 ──
        // 上次补全失败:在本次搜索前重新触发,避免搜索结果永久缺页。
        if (catalogReady && completePending && !catalogComplete) {
            completePending = false
            completeJob?.cancel()
            completeJob = viewModelScope.launch { completeCatalog(repository.currentProvider) }
        }
        // provider 切换或首次:快速加载前几页(秒回)。
        if (!isCatalogCurrent || !catalogReady) {
            refreshCatalogFast()
            if (!catalogReady) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    results = emptyList(),
                    error = "目录加载失败，请重试",
                )
                return
            }
            // 快速页已就绪即可出结果；剩余页后台补全，不阻塞首搜。
            completeJob?.cancel()
            completeJob = viewModelScope.launch { completeCatalog(repository.currentProvider) }
        }
        // 同一视频可能出现在多个分类,去重避免列表 key 冲突。
        // 用 query 对标题做包含匹配；也匹配 creators（若有）。
        val q = query.trim()
        // 先在主线程对缓存做快照(缓存仅主线程读写),再在 Default 上过滤,
        // 避免大目录每次按键都在主线程全量扫描卡 UI。
        val snapshot = catalogCache.values.toList()
        val results = withContext(Dispatchers.Default) {
            snapshot.flatten()
                .distinctBy { it.id }
                .filter { it.title.contains(q, ignoreCase = true) }
        }
        // 远程两级全失败且本地无匹配:显式报网络错误,否则用户把"搜索服务挂了"
        // 误读成"没有这个视频"。
        val searchError = if (results.isEmpty() && remoteFailed) {
            "搜索服务暂不可用（网络异常），请检查网络后重试"
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(isSearching = false, results = results, error = searchError)
    }

    // 目录缓存是否与当前 provider 一致
    private val isCatalogCurrent: Boolean
        get() = cachedProvider == repository.currentProvider

    private companion object {
        // 目录拉取并发上限:与 HomeViewModel 同值,防止弱网打满 OkHttp 连接池。
        const val SEARCH_FETCH_CONCURRENCY = 4
        // 首搜只需前 2 页(36 条)，秒回；旧实现拉满 5 页导致冷启动慢 + 内存占用高。
        const val FAST_PAGES = 2
        // 每分类最多拉取的页数(后台补全用)，控制本地目录规模(18 条/页)。
        const val MAX_PAGES_PER_CATEGORY = 5
    }
}
