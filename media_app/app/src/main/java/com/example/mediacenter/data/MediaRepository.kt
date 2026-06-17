package com.example.mediacenter.data

enum class VideoDetailsCacheScope {
    Category,
    Search,
}

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
    private val categoryDetailsCache: VideoDetailsCache? = null,
    private val searchDetailsCache: VideoDetailsCache? = null,
) {
    suspend fun categories(): List<ContentCategory> {
        return api.categories().items
    }

    suspend fun search(
        query: String,
        categoryId: String?,
        page: Int,
        pageSize: Int,
    ): SearchResponse {
        val response = if (query.isNotBlank()) {
            api.search(query, page = page, pageSize = pageSize)
        } else if (categoryId != null) {
            api.searchCategory(categoryId, page = page, pageSize = pageSize)
        } else {
            error("请选择一个分类或输入关键词")
        }
        return response.copy(items = response.items.filter { it.videoKind == "vod" })
    }

    suspend fun details(
        video: VideoSummary,
        cacheScope: VideoDetailsCacheScope = VideoDetailsCacheScope.Category,
    ): VideoDetails {
        val cache = detailsCacheFor(cacheScope)
        // 缓存不持久化 thumbnailHeaders/thumbnailUrl (签名 URL 不落盘)。
        // toVideoDetails() 已用 video 的值做 fallback;这里补充与 API 路径相同的
        // 归一化,保持两条路径结构一致。
        cache?.get(video)?.let { cached ->
            return cached.copy(
                thumbnailUrl = cached.thumbnailUrl ?: video.thumbnailUrl,
                thumbnailHeaders = cached.thumbnailHeaders.ifEmpty { video.thumbnailHeaders },
            )
        }
        val details = try {
            api.details(video.id)
        } catch (error: Throwable) {
            if (error.isNoPlayableStream) {
                val unavailableDetails = video.toUnavailableDetails()
                cache?.put(unavailableDetails)
                return unavailableDetails
            }
            throw error
        }
        require(details.videoKind == "vod") { "当前只支持普通视频点播" }
        val resolvedThumbnailUrl = details.thumbnailUrl ?: video.thumbnailUrl
        val normalizedDetails = details.copy(
            thumbnailUrl = resolvedThumbnailUrl,
            thumbnailHeaders = when {
                details.thumbnailUrl == null -> video.thumbnailHeaders
                details.thumbnailHeaders.isNotEmpty() -> details.thumbnailHeaders
                details.thumbnailUrl == video.thumbnailUrl -> video.thumbnailHeaders
                else -> details.thumbnailHeaders
            },
            thumbnailDelivery = if (details.thumbnailUrl != null) details.thumbnailDelivery else video.thumbnailDelivery,
        )
        cache?.put(normalizedDetails)
        return normalizedDetails
    }

    suspend fun prefetchDetails(
        video: VideoSummary,
        cacheScope: VideoDetailsCacheScope = VideoDetailsCacheScope.Category,
    ) {
        details(video, cacheScope)
    }

    /**
     * 解析视频的可播放选项。按 [video] 的 provider 分流:
     *  - 国内可直连源(当前 bilibili):**跳过** server `/videos/{id}/playback`,用 nativeId
     *    调本地 [bilibiliApi] 拿真实播放直链。能否本地解析是 provider 的固有属性,
     *    不依赖 server 在 PlaybackOption 里塞任何标记。
     *  - 国外源(youtube 等):走 server,由 server + vod_extractor 解析。
     */
    suspend fun playbackOptions(
        video: VideoDetails,
        preferProxy: Boolean = false,
    ): List<PlaybackOption> {
        val provider = video.provider
        val nativeId = video.nativeId
        if (provider == "bilibili" && !nativeId.isNullOrEmpty()) {
            return bilibiliApi.resolvePlayback(nativeId)
        }
        return try {
            api.playback(video.id, preferProxy).items.filter { it.kind == "vod" }
        } catch (error: Throwable) {
            if (error.isNoPlayableStream) emptyList() else throw error
        }
    }

    private fun detailsCacheFor(cacheScope: VideoDetailsCacheScope): VideoDetailsCache? {
        return when (cacheScope) {
            VideoDetailsCacheScope.Category -> categoryDetailsCache
            VideoDetailsCacheScope.Search -> searchDetailsCache
        }
    }

    private fun VideoSummary.toUnavailableDetails(): VideoDetails {
        return VideoDetails(
            id = id,
            videoKind = videoKind,
            title = title,
            description = description,
            year = year,
            creators = creators,
            thumbnailUrl = thumbnailUrl,
            thumbnailHeaders = thumbnailHeaders,
            thumbnailDelivery = thumbnailDelivery,
            durationSeconds = durationSeconds,
            playbackKind = playbackKind,
            playbackOptions = emptyList(),
        )
    }
}
