package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val categories: List<ContentCategory> = emptyList(),
    val categoryVideos: Map<String, List<VideoSummary>> = emptyMap(),
    // 加载失败(非空内容)的分类 id 集合:与"真的没有内容"区分,UI 展示失败文案。
    val categoryErrors: Set<String> = emptySet(),
    val providers: List<ProviderInfo> = emptyList(),
    val currentProvider: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: MediaRepository = MediaRepositoryHolder.repository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // App 内更新:检查/下载/弹窗状态统一在进程级协调器(AppUpdateHolder)上,
    // 与设置页共享同一份;这里只暴露其状态流,进入首页时顺手发起一次检查。
    val updateState: StateFlow<UpdateDialogState?> = AppUpdateHolder.updateState

    // 跟踪当前加载任务:切换 provider / 刷新时取消旧任务,
    // 避免慢的旧请求后完成覆盖新状态(旧 provider 内容配新 currentProvider)。
    private var loadJob: Job? = null

    // 分类第一页内存缓存:首页重进/重试不重复拉取(见 loadHome 注释)。
    // 带 provider 标记:切换 provider 后自然失效;只缓存成功结果。
    private data class FirstPageCacheEntry(
        val provider: String,
        val items: List<VideoSummary>,
        val timestamp: Long,
    )

    private val firstPageCache = mutableMapOf<String, FirstPageCacheEntry>()

    init {
        loadHome()
        // 自动检查更新:协调器内进程级只查一次,失败/下载中不重复发起
        AppUpdateHolder.requestCheck()
    }

    fun loadHome() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val providers = repository.providers()
                _uiState.value = _uiState.value.copy(
                    providers = providers,
                    currentProvider = repository.currentProvider,
                )
                val cats = repository.categories()
                // 并行拉取各分类第一页,避免分类多时首页加载时间线性增长。
                // 用信号量限制并发(弱网/老设备上打满连接池会互相拖慢);
                // 单个分类失败(超时/5xx)不拖垮整个首页:该分类显示失败文案,
                // 其余正常展示,下拉/重进会整体刷新。
                //
                // 分类第一页内存缓存(TTL 10 分钟):首页每次加载(重进/断网恢复/
                // 手动重试)都全量重拉所有分类,弱网盒子上重复开销大且慢;缓存命中
                // 直接秒开,超时/切 provider 才真正请求。缓存只存成功结果。
                val results = coroutineScope {
                    val semaphore = Semaphore(HOME_CATEGORY_CONCURRENCY)
                    cats.map { cat ->
                        async {
                            semaphore.withPermit {
                                val provider = repository.currentProvider
                                val cached = firstPageCache[cat.id]
                                if (cached != null && cached.provider == provider &&
                                    System.currentTimeMillis() - cached.timestamp < FirstPageCacheTtlMs
                                ) {
                                    cat.id to Result.success(cached.items)
                                } else {
                                    val result = runCatching { repository.catalogPage(cat.id).items.orEmpty() }
                                    result.onSuccess {
                                        firstPageCache[cat.id] = FirstPageCacheEntry(provider, it, System.currentTimeMillis())
                                        // 缓存有上限:超限淘汰最早条目(分类数量有限,防御性兜底)
                                        while (firstPageCache.size > MaxFirstPageCacheEntries) {
                                            firstPageCache.remove(firstPageCache.keys.first())
                                        }
                                    }
                                    cat.id to result
                                }
                            }
                        }
                    }.awaitAll()
                }
                val videos = mutableMapOf<String, List<VideoSummary>>()
                val failed = mutableSetOf<String>()
                for ((catId, result) in results) {
                    result.onSuccess { videos[catId] = it }.onFailure { failed.add(catId) }
                }
                _uiState.value = _uiState.value.copy(
                    categories = cats,
                    categoryVideos = videos,
                    categoryErrors = failed,
                    isLoading = false,
                )
            } catch (e: Exception) {
                // 被取消的任务(CancellationException)不当作错误展示
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 原始异常常是英文技术信息,直接上屏对 TV 用户不友好;记日志、给固定文案
                AppLog.e("Home", "loadHome failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = "首页加载失败，请重试")
            }
        }
    }

    /** 切换视频源后重新加载首页内容。 */
    fun selectProvider(provider: String) {
        repository.setProvider(provider)
        loadHome()
    }

    private companion object {
        // 首页并行拉取分类第一页的并发上限:弱网/老设备上超过该值的并发
        // 只会互相抢占连接与带宽,整体更慢。
        const val HOME_CATEGORY_CONCURRENCY = 4
        // 分类第一页缓存有效期:10 分钟内重进首页直接用缓存,不重复请求。
        const val FirstPageCacheTtlMs = 10 * 60 * 1000L
        // 缓存条目上限(防御性兜底,正常分类数量远小于此)。
        const val MaxFirstPageCacheEntries = 64
    }
}

class HomeViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel() as T
    }
}
