package com.example.mediacenter.data

import kotlinx.serialization.Serializable

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String? = null,
)

@Serializable
data class ProvidersResponse(
    val items: List<ProviderInfo>? = null,
)

@Serializable
data class ProviderCredential(
    val provider: String,
    val fields: Map<String, String>? = null,
)

@Serializable
data class CredentialsResponse(
    val credentials: List<ProviderCredential>? = null,
)

@Serializable
data class ContentCategory(
    val id: String,
    val title: String,
    val description: String? = null,
)

@Serializable
data class CategoriesResponse(
    val items: List<ContentCategory>? = null,
)

@Serializable
data class VideoSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
)

@Serializable
data class VideosResponse(
    val category: ContentCategory? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val items: List<VideoSummary>? = null,
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
    val description: String? = null,
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
    val audioUrl: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val qn: Int = 0,
)

// PlaybackPages 记录视频的多P信息:当前播放第几页、总共几页、所有分页的 cid 和时长。
data class PlaybackPages(
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val pageCids: List<Long> = emptyList(),
    val pageTitles: List<String> = emptyList(),
    val pageDurations: List<Long> = emptyList(),
) {
    val currentPageDurationSeconds: Long? get() = pageDurations.getOrNull(currentPage)
}

// PlaybackResult 是解析播放地址的完整结果:播放选项列表 + 多P信息 + 可用清晰度。
data class PlaybackResult(
    val options: List<PlaybackOption>,
    val pages: PlaybackPages = PlaybackPages(),
    // 该视频全部可用清晰度(播放页清晰度切换弹窗用)。由 playurl 接口的
    // accept_quality/accept_description 得到,与 options 的当前单条 durl 无关;
    // 切换清晰度必须按新 qn 重新解析(见 BilibiliApi.buildPlaybackOptions 注释)。
    val availableQualities: List<PlaybackQuality> = emptyList(),
)

// 一个可选清晰度:qn 为 B 站 playurl 的清晰度编码,label 为显示文本(如 "1080P")。
data class PlaybackQuality(
    val qn: Int,
    val label: String,
)

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
)
