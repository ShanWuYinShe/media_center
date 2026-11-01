package com.example.mediacenter.ui.home

import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePresenterTest {
    private val cat1 = ContentCategory(id = "movies", title = "电影")
    private val cat2 = ContentCategory(id = "tv", title = "电视剧")
    private val video1 = VideoSummary(id = "BV1", title = "电影一", durationSeconds = 3600)
    private val video2 = VideoSummary(id = "BV2", title = "剧一", durationSeconds = 1800)

    private fun state(
        watchEntries: List<WatchEntry> = emptyList(),
        categories: List<ContentCategory> = listOf(cat1, cat2),
        videos: Map<String, List<VideoSummary>> = mapOf("movies" to listOf(video1), "tv" to listOf(video2)),
    ) = HomeUiState(
        categories = categories,
        categoryVideos = videos,
        currentProvider = "bilibili",
        watchEntries = watchEntries,
        isLoading = false,
    )

    @Test
    fun `继续观看只保留当前源且位置足够的条目并作为 Hero`() {
        val entries = listOf(
            WatchEntry(videoId = "A", provider = "bilibili", title = "A", thumbnailUrl = "", positionSeconds = 60, durationSeconds = 120),
            WatchEntry(videoId = "B", provider = "youtube", title = "B", thumbnailUrl = "", positionSeconds = 60, durationSeconds = 120),
            WatchEntry(videoId = "C", provider = "bilibili", title = "C", thumbnailUrl = "", positionSeconds = 5, durationSeconds = 120),
        )
        val content = buildHomeContent(state(entries))
        assertEquals(listOf("A"), content.continueEntries.map { it.videoId })
        assertEquals("A", content.hero?.video?.id)
        assertEquals("继续观看", content.hero?.badge)
        assertEquals(0.5f, content.hero?.progress)
    }

    @Test
    fun `无继续观看时 Hero 取首个有内容的分类第一条`() {
        val content = buildHomeContent(state())
        assertEquals("BV1", content.hero?.video?.id)
        assertEquals("精选", content.hero?.badge)
        assertEquals("电影 · 1:00:00", content.hero?.meta)
    }

    @Test
    fun `Hero 优先选有封面的条目`() {
        val noCover = VideoSummary(id = "NO_COVER", title = "无封面", durationSeconds = 100)
        val withCover = VideoSummary(
            id = "WITH_COVER",
            title = "有封面",
            thumbnailUrl = "https://example.com/a.jpg",
            durationSeconds = 100,
        )
        val content = buildHomeContent(
            state(videos = mapOf("movies" to listOf(noCover, withCover))),
        )
        assertEquals("WITH_COVER", content.hero?.video?.id)
    }

    @Test
    fun `空分类数据不产生 Hero`() {
        val content = buildHomeContent(state(categories = emptyList(), videos = emptyMap()))
        assertNull(content.hero)
        assertEquals(emptyList<WatchEntry>(), content.continueEntries)
    }
}
