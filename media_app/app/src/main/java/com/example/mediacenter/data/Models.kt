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

// PlaybackOption 表示一条真实可播放的流(BilibiliApi 按指定 qn 解析后产出)。
// 每个选项的 url 都是对应 qn 的实际流地址;切清晰度需按新 qn 重新解析,而非复用旧 url。
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

// 视频可切换的清晰度列表(标签 + qn 编码),由 playurl 响应的 accept_quality 派生。
data class QualityOption(
    val qn: Int,
    val label: String,
)

// PlaybackResult 是解析播放地址的完整结果:当前清晰度的真实流 + 多P信息 + 可切换清晰度列表。
data class PlaybackResult(
    val options: List<PlaybackOption>,
    val pages: PlaybackPages = PlaybackPages(),
    val availableQualities: List<QualityOption> = emptyList(),
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
