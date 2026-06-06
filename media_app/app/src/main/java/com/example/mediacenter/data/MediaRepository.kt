package com.example.mediacenter.data

class MediaRepository(private val api: MediaApi) {
    suspend fun categories(): List<ContentCategory> {
        return api.categories().items
    }

    suspend fun search(
        query: String,
        categoryId: String?,
        page: Int,
        pageSize: Int,
    ): SearchResponse {
        val response = if (categoryId == null) {
            api.search(query, page = page, pageSize = pageSize)
        } else {
            api.searchCategory(categoryId, query, page = page, pageSize = pageSize)
        }
        return response.copy(items = response.items.filter { it.videoKind == "vod" })
    }

    suspend fun details(video: VideoSummary): VideoDetails {
        val details = api.details(video.id)
        require(details.videoKind == "vod") { "当前只支持普通视频点播" }
        return details
    }
}
