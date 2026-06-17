package com.example.mediacenter.data

import kotlinx.serialization.Serializable

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String = "",
)

@Serializable
data class ProvidersResponse(
    val items: List<ProviderInfo> = emptyList(),
)

@Serializable
data class ContentCategory(
    val id: String,
    val title: String,
    val description: String = "",
)

@Serializable
data class CategoriesResponse(
    val items: List<ContentCategory> = emptyList(),
)

@Serializable
data class VideoSummary(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
)

@Serializable
data class VideosResponse(
    val category: ContentCategory? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val items: List<VideoSummary> = emptyList(),
)

// SearchResponse 是 Repository 兼容包装:新 server API 没有 search endpoint,
// Repository 用 categoryVideos 构造兼容结构,让 ViewModel 的分页逻辑不改。
@Serializable
data class SearchResponse(
    val query: String = "",
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val items: List<VideoSummary> = emptyList(),
)

// VideoDetails 是 VideoSummary 的 UI 扩展:加上播放选项(BilibiliApi 解析后填入)。
// 不再从 server 拿——server 只提供目录数据,播放地址由 App 端按 provider 自己解析。
@Serializable
data class VideoDetails(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val playbackOptions: List<PlaybackOption> = emptyList(),
)

@Serializable
data class PlaybackOption(
    val id: String,
    val kind: String,
    val label: String,
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val releaseNotes: String = "",
    val publishedAt: String = "",
)
