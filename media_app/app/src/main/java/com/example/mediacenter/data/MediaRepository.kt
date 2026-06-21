package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
) {
    // 当前选中的 provider(默认 bilibili)。loadProviders 取到列表后自动选第一个。
    @Volatile var currentProvider: String = "bilibili"
        private set

    suspend fun providers(): List<ProviderInfo> {
        refreshCredentials()
        val items = api.providers().items
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
            for (cred in response.credentials) {
                if (cred.provider == "bilibili" && cred.fields.isNotEmpty()) {
                    bilibiliApi.updateCredentials(cred.fields)
                }
            }
        } catch (e: Exception) {
            AppLog.w("Repo", "failed to fetch provider credentials, using no-credential mode", e)
        }
    }

    // 兼容包装:ViewModel 仍调 categories() 无参版本。
    suspend fun categories(): List<ContentCategory> = api.categories(currentProvider).items

    suspend fun categories(provider: String): List<ContentCategory> = api.categories(provider).items

    // 兼容包装:ViewModel 的 search/loadMore 仍调此方法,Repository 内部用
    // categoryVideos 替代旧的 search endpoint(新 API 没有 search)。
    suspend fun search(
        query: String,
        categoryId: String?,
        page: Int = 1,
        pageSize: Int = 18,
    ): SearchResponse {
        if (categoryId == null) {
            return SearchResponse(query = query, page = page, pageSize = pageSize)
        }
        val resp = api.categoryVideos(currentProvider, categoryId, page, pageSize)
        return SearchResponse(
            query = query,
            page = resp.page,
            pageSize = resp.pageSize,
            total = resp.total,
            items = resp.items,
        )
    }

    // 兼容包装:不再调 server details 接口,本地从 VideoSummary 构造 VideoDetails。
    // server 只提供目录数据(id/title/desc/thumb),播放地址由 playbackOptions 按需解析。
    suspend fun details(video: VideoSummary): VideoDetails {
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
