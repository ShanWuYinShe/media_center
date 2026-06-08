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
    val durationSeconds: Long? = null,
    val playbackKind: String = "vod",
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
    val durationSeconds: Long? = null,
    val playbackKind: String = "vod",
    val playbackOptions: List<PlaybackOption> = emptyList(),
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
)
