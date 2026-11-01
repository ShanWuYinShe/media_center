package com.example.mediacenter.ui.home

import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.util.formatSeconds

/** 首页由状态派生的展示数据(纯函数,可在 JVM 单测)。 */
data class HomeContent(
    val continueEntries: List<WatchEntry>,
    val continuePageById: Map<String, Int>,
    val hero: FeaturedVideo?,
)

/**
 * 把目录状态 + 本地观看历史装配成首页展示模型:
 * 有继续观看时 Hero 取最新一条(带进度),否则取首个有内容的分类第一条。
 */
fun buildHomeContent(state: HomeUiState): HomeContent {
    val continueEntries = state.watchEntries.filter {
        it.provider == state.currentProvider && it.positionSeconds >= 30
    }
    val hero = continueEntries.firstOrNull()?.let { entry ->
        FeaturedVideo(
            video = entry.toVideoSummary(),
            page = entry.page,
            progress = if (entry.durationSeconds > 0) {
                (entry.positionSeconds.toFloat() / entry.durationSeconds).coerceIn(0f, 1f)
            } else {
                null
            },
            badge = "继续观看",
            meta = continueMeta(entry.positionSeconds, entry.durationSeconds),
            description = null,
        )
    } ?: state.categories.firstOrNull { !state.categoryVideos[it.id].isNullOrEmpty() }?.let { category ->
        val videos = state.categoryVideos.getValue(category.id)
        // Hero 是大图位:优先选有封面的条目,避免首页首屏出现整块黑色空图。
        val video = videos.firstOrNull { !it.thumbnailUrl.isNullOrBlank() } ?: videos.first()
        val duration = video.durationSeconds?.takeIf { it > 0 }?.let(::formatSeconds)
        FeaturedVideo(
            video = video,
            badge = "精选",
            meta = listOfNotNull(category.title, duration).joinToString(" · "),
            description = video.description,
        )
    }
    return HomeContent(
        continueEntries = continueEntries,
        continuePageById = continueEntries.associate { it.videoId to it.page },
        hero = hero,
    )
}
