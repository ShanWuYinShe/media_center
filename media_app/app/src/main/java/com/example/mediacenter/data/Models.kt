package com.example.mediacenter.data

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(
    val query: String,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val items: List<VideoSummary>,
)

@Serializable
data class CategoryResponse(
    val items: List<ContentCategory>,
)

@Serializable
data class ContentCategory(
    val id: String,
    val title: String,
    val description: String,
)

@Serializable
data class VideoSummary(
    val id: String,
    val videoKind: String = "vod",
    val title: String,
    val description: String = "",
    val year: String? = null,
    val creators: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    val thumbnailDelivery: String = DeliveryMode.DIRECT,
    val durationSeconds: Long? = null,
    val playbackKind: String = "vod",
    // 透传 catalog 原始来源(如 "bilibili" + "BV...");App 端据此判断是否需要
    // 自己解析播放地址(见 MediaRepository.playbackOptions 按 provider 分流)。
    val provider: String? = null,
    val nativeId: String? = null,
)

@Serializable
data class VideoDetails(
    val id: String,
    val videoKind: String = "vod",
    val title: String,
    val description: String = "",
    val year: String? = null,
    val creators: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val thumbnailHeaders: Map<String, String> = emptyMap(),
    val thumbnailDelivery: String = DeliveryMode.DIRECT,
    val durationSeconds: Long? = null,
    val playbackKind: String = "vod",
    val playbackOptions: List<PlaybackOption> = emptyList(),
    val provider: String? = null,
    val nativeId: String? = null,
)

@Serializable
data class PlaybackOptionsResponse(
    val items: List<PlaybackOption> = emptyList(),
)

@Serializable
data class PlaybackOption(
    val id: String,
    val kind: String,
    val label: String,
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val delivery: String = DeliveryMode.DIRECT,
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

/**
 * 资源下发方式，决定 App 如何获取缩略图 / 播放流：
 *  - [DIRECT]：国内可直连源，服务端只下发上游 URL + companion headers，App 直连上游。
 *  - [PROXY]：国内不可直连的国外源，服务端通过 /asset 代理回源，下发的是签名代理地址；
 *    上游请求头由服务端附加，App 端 headers 为空，URL 也不可持久化。
 */
object DeliveryMode {
    const val DIRECT = "direct"
    const val PROXY = "proxy"
}
