package com.example.mediacenter.data

enum class VideoDetailsCacheScope {
    Category,
    Search,
}

class MediaRepository(
    private val api: MediaApi,
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
        cache?.get(video)?.let { return it }
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
        val normalizedDetails = details.copy(
            thumbnailUrl = details.thumbnailUrl ?: video.thumbnailUrl,
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

    suspend fun playbackOptions(videoId: String): List<PlaybackOption> {
        return try {
            api.playback(videoId).items.filter { it.kind == "vod" }
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
            durationSeconds = durationSeconds,
            playbackKind = playbackKind,
            playbackOptions = emptyList(),
        )
    }
}
