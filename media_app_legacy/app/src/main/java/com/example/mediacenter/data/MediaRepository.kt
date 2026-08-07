package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
) {
    // 当前选中的 provider(默认 bilibili)。loadProviders 取到列表后自动选第一个。
    @Volatile var currentProvider: String = "bilibili"
        private set

    // HTTPS 握手失败后是否已降级到明文 HTTP(供诊断弹窗显示)。
    val httpFallbackActive: Boolean
        get() = api.httpFallbackActive

    private companion object {
        // 客户端关键词搜索时最多拉取的分类页数(每页 ≤ pageSize 条),用于扩大命中范围。
        // 服务端单分类通常几百条,5 页 × 18 ≈ 90 条已足够覆盖常见搜索词。
        const val SEARCH_MAX_PAGES = 5
    }

    suspend fun providers(): List<ProviderInfo> {
        refreshCredentials()
        val items = api.providers().items.orEmpty()
        if (items.isNotEmpty()) {
            currentProvider = items.first().id
        }
        return items
    }

    // 从 server 拉取 provider 凭证并注入到对应的 API 客户端。
    // 失败时静默降级为无凭证模式(不影响基本播放)。
    suspend fun refreshCredentials() {
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
    suspend fun categories(): List<ContentCategory> = api.categories(currentProvider).items.orEmpty()

    suspend fun categories(provider: String): List<ContentCategory> = api.categories(provider).items.orEmpty()

    // 服务端无 search endpoint;分类浏览用 categoryVideos,关键词搜索在已加载的
    // 分类数据上做客户端标题/简介过滤。query 非空时拉取多页(最多 SEARCH_MAX_PAGES)
    // 以扩大命中范围,过滤后分页返回;query 为空则原样返回服务端分页。
    suspend fun search(
        query: String,
        categoryId: String?,
        page: Int = 1,
        pageSize: Int = 18,
    ): SearchResponse {
        if (categoryId == null) {
            return SearchResponse(query = query, page = page, pageSize = pageSize)
        }
        val trimmed = query.trim()
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
    fun details(video: VideoSummary): VideoDetails {
        return VideoDetails(
            id = video.id,
            title = video.title,
            description = video.description,
            thumbnailUrl = video.thumbnailUrl,
            durationSeconds = video.durationSeconds,
        )
    }

    // 解析播放地址:返回播放选项+多P信息。当前所有视频来自 bilibili catalog。
    suspend fun playbackOptions(video: VideoDetails): PlaybackResult {
        return bilibiliApi.resolvePlayback(video.id)
    }

    // 指定清晰度解析播放地址。
    suspend fun playbackOptions(video: VideoDetails, qn: Int): PlaybackResult {
        return bilibiliApi.resolvePlayback(video.id, qn)
    }

    // 解析多P视频指定分页的播放地址。
    suspend fun resolvePagePlayback(bvid: String, pageIndex: Int): List<PlaybackOption> {
        return bilibiliApi.resolvePagePlayback(bvid, pageIndex)
    }
}
