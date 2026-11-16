package com.example.mediacenter.data

import android.content.Context
import android.content.SharedPreferences
import com.example.mediacenter.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
    private val youtubeApi: YouTubeApi = YouTubeApi(api),
    private val prefs: SharedPreferences? = null,
    // 首页首屏快照落盘用;为 null 时(测试/无 Context)快照功能自动关闭。
    private val context: Context? = null,
) : MediaRepositoryApi {
    // 当前选中的 provider(默认 bilibili)。首次加载 providers 后仅在无持久化
    // 选择/原选择下线时自动选第一个;之后以用户选择为准。
    @Volatile override var currentProvider: String =
        prefs?.getString(KEY_CURRENT_PROVIDER, null) ?: "bilibili"
        private set
    private val providerInitialized = AtomicBoolean(false)

    // HTTPS 握手失败后是否已降级到明文 HTTP(供诊断弹窗显示)。
    override val httpFallbackActive: Boolean
        get() = api.httpFallbackActive

    private companion object {
        const val KEY_CURRENT_PROVIDER = "current_provider"
        // legacy 只跑在 API19 老盒子上:YouTube 服务端按清晰度降序返回全部档位,
        // 自动起播收敛到 ≤720P(弱解码/弱网不先卡缓冲);手动切清晰度不受影响。
        const val MAX_INITIAL_QUALITY_QN = 720
        // 客户端关键词搜索时最多拉取的分类页数(每页 ≤ pageSize 条),用于扩大命中范围。
        // 服务端单分类通常几百条,5 页 × 18 ≈ 90 条已足够覆盖常见搜索词。
        const val SEARCH_MAX_PAGES = 5
    }

    override suspend fun providers(): List<ProviderInfo> {
        refreshCredentials()
        val items = api.providers().items.orEmpty()
        applyProviderSelection(items)
        return items
    }

    /**
     * 首页启动聚合:一次请求拿到 providers + 分类(替代串行的 providers→categories,
     * 老盒子弱网下省一个 RTT);凭证请求与之并行,凭证失败不影响目录。
     * 旧服务端没有聚合端点时自动退回原有两次请求。
     */
    override suspend fun bootstrap(): Pair<List<ProviderInfo>, List<ContentCategory>> {
        val requestedProvider = currentProvider
        return coroutineScope {
            val credentials = async { refreshCredentials() }
            val response = try {
                api.bootstrap(requestedProvider)
            } catch (e: Exception) {
                credentials.cancel()
                AppLog.w("Repo", "bootstrap failed, fallback to providers+categories: ${e.message}", e)
                return@coroutineScope providers() to categories()
            }
            credentials.await()
            val providers = response.providers.orEmpty()
            applyProviderSelection(providers)
            val categories = if (currentProvider == requestedProvider) {
                response.categories.orEmpty()
            } else {
                // 持久化的 provider 已下线、bootstrap 期间切到第一个:分类按新 provider 重取。
                api.categories(currentProvider).items.orEmpty()
            }
            providers to categories
        }
    }

    /** 首次拿到 providers 时恢复/校正当前 provider(持久化选择优先)。 */
    private fun applyProviderSelection(items: List<ProviderInfo>) {
        if (items.isEmpty() || !providerInitialized.compareAndSet(false, true)) return
        val restored = prefs?.getString(KEY_CURRENT_PROVIDER, null)
        if (restored.isNullOrEmpty() || items.none { it.id == restored }) {
            currentProvider = items.first().id
            persistProvider()
        }
    }

    override fun loadHomeSnapshot(): HomeSnapshot? =
        context?.let { HomeSnapshotStore.load(it) }

    override fun saveHomeSnapshot(snapshot: HomeSnapshot) {
        context?.let { HomeSnapshotStore.save(it, snapshot) }
    }

    /** 用户手动切换视频源,并持久化到 SharedPreferences。 */
    override fun selectProvider(provider: String) {
        if (provider == currentProvider) return
        currentProvider = provider
        persistProvider()
        AppLog.i("Repo", "switch provider to $provider")
    }

    private fun persistProvider() {
        prefs?.edit()?.putString(KEY_CURRENT_PROVIDER, currentProvider)?.apply()
    }

    // 从 server 拉取 provider 凭证并注入到对应的 API 客户端。
    // 失败时静默降级为无凭证模式(不影响基本播放)。
    override suspend fun refreshCredentials() {
        try {
            val response = api.credentials()
            for (cred in response.credentials.orEmpty()) {
                if (cred.provider == "bilibili" && cred.fields?.isNotEmpty() == true) {
                    bilibiliApi.updateCredentials(cred.fields.orEmpty())
                }
            }
        } catch (e: Exception) {
            AppLog.w("Repo", "failed to fetch provider credentials, using no-credential mode", e)
        }
    }

    // 兼容包装:ViewModel 仍调 categories() 无参版本。
    override suspend fun categories(): List<ContentCategory> = api.categories(currentProvider).items.orEmpty()

    // 服务端无全局 search endpoint;分类浏览用 categoryVideos,关键词搜索在已加载的
    // 分类数据上做客户端标题/简介过滤。query 非空时拉取多页(最多 SEARCH_MAX_PAGES)
    // 以扩大命中范围,过滤后分页返回;query 为空则原样返回服务端分页。
    //
    // 例外:youtube provider 的关键词搜索走实时上游通道(Data API,见 YouTubeApi)——
    // YouTube 内容不要求先入库,分类内过滤搜不到实时内容;此时忽略 categoryId。
    override suspend fun search(
        query: String,
        categoryId: String?,
        page: Int,
        pageSize: Int,
    ): SearchResponse {
        val trimmed = query.trim()
        if (currentProvider == "youtube") {
            if (trimmed.isNotEmpty()) {
                val resp = youtubeApi.search(trimmed)
                val items = resp.items.orEmpty()
                return SearchResponse(
                    query = query,
                    page = 1,
                    pageSize = items.size,
                    total = items.size,
                    items = items,
                )
            }
            if (categoryId == null) {
                return SearchResponse(query = query, page = page, pageSize = pageSize)
            }
            // YouTube 分类由服务端 /providers/youtube/categories/{id}/videos
            // 映射为一次实时 ytsearch;单页结果,始终从第 1 页取。
            val resp = api.categoryVideos(currentProvider, categoryId, page = 1, pageSize = 20)
            val items = resp.items.orEmpty()
            return SearchResponse(
                query = query,
                page = 1,
                pageSize = items.size,
                total = items.size,
                items = items,
            )
        }
        if (categoryId == null) {
            return SearchResponse(query = query, page = page, pageSize = pageSize)
        }
        if (trimmed.isEmpty()) {
            // 纯分类浏览:直接转发服务端分页
            val resp = api.categoryVideos(currentProvider, categoryId, page, pageSize)
            return SearchResponse(
                query = query,
                page = resp.page,
                pageSize = resp.pageSize,
                total = resp.total,
                items = resp.items.orEmpty(),
            )
        }
        // 关键词搜索:拉取多页后客户端过滤。只做一次(非分页拉取),命中数足够时截断。
        val keywords = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val accumulated = ArrayList<VideoSummary>(SEARCH_MAX_PAGES * pageSize)
        for (p in 1..SEARCH_MAX_PAGES) {
            val resp = api.categoryVideos(currentProvider, categoryId, p, pageSize)
            val items = resp.items.orEmpty()
            if (items.isEmpty()) break
            accumulated.addAll(items)
            if (accumulated.size >= resp.total) break
        }
        val filtered = accumulated.filter { v ->
            val title = v.title
            val desc = v.description.orEmpty()
            // 所有关键词都命中(标题或简介,忽略大小写)才算匹配
            keywords.all { kw ->
                title.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)
            }
        }
        return SearchResponse(
            query = query,
            page = 1,
            pageSize = filtered.size,
            total = filtered.size,
            // 一次性返回全部命中(已分页拉取过);UI 的 loadMore 会因 canLoadMore 判断禁用
            items = filtered,
        )
    }

    // 兼容包装:不再调 server details 接口,本地从 VideoSummary 构造 VideoDetails。
    // server 只提供目录数据(id/title/desc/thumb),播放地址由 playbackOptions 按需解析。
    override fun details(video: VideoSummary): VideoDetails {
        return VideoDetails(
            id = video.id,
            title = video.title,
            description = video.description,
            thumbnailUrl = video.thumbnailUrl,
            thumbnailUrlLarge = video.thumbnailUrlLarge ?: video.thumbnailUrl,
            durationSeconds = video.durationSeconds,
        )
    }

    // 解析播放地址:按 provider 分发。bilibili 走 WBI 签名 + view + playurl;
    // youtube 交给 server 的解析代理(一次返回全部清晰度档,切档由播放层换源)。
    override suspend fun playbackOptions(video: VideoDetails): PlaybackResult = when (currentProvider) {
        "youtube" -> capInitialQuality(youtubeApi.resolvePlayback(video.id))
        else -> bilibiliApi.resolvePlayback(video.id)
    }

    /** 自动起播档位收敛:把 ≤720P 的最高档提到首位,其余档位保留用于清晰度菜单。 */
    private fun capInitialQuality(result: PlaybackResult): PlaybackResult {
        val options = result.options
        if (options.size < 2) return result
        val activeQn = options.first().qn ?: return result
        if (activeQn <= MAX_INITIAL_QUALITY_QN) return result
        val preferred = options
            .filter { it.qn != null && it.qn <= MAX_INITIAL_QUALITY_QN }
            .maxByOrNull { it.qn ?: 0 }
            ?: return result
        return result.copy(options = listOf(preferred) + options.filter { it.url != preferred.url })
    }

    // 指定清晰度解析播放地址。youtube 端 qn 被忽略(server 忽略该参数,返回全档)。
    override suspend fun playbackOptions(video: VideoDetails, qn: Int): PlaybackResult = when (currentProvider) {
        "youtube" -> youtubeApi.resolvePlayback(video.id)
        else -> bilibiliApi.resolvePlayback(video.id, qn)
    }

    // 解析多P视频指定分页的播放地址。多P是 bilibili catalog 的概念
    // (youtube 恒单页,正常流程不会走到这里,守卫防误用)。
    override suspend fun resolvePagePlayback(bvid: String, pageIndex: Int): List<PlaybackOption> {
        if (currentProvider != "bilibili") {
            throw IllegalStateException("「${currentProvider}」视频源不支持多P切换")
        }
        return bilibiliApi.resolvePagePlayback(bvid, pageIndex)
    }
}
