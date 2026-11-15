package com.example.mediacenter.ui.player

import com.example.mediacenter.data.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextPrefetchTest {
    private fun video(id: String) = VideoSummary(id = id, title = "t")

    @Test
    fun `多P下一页拼 _pN`() {
        assertEquals("BV1x_p3", nextPrefetchTargetId("BV1x", "bilibili", PlaybackNext.Page(2)))
    }

    @Test
    fun `多P带分P后缀时还原基础 bvid`() {
        assertEquals("BV1x_p2", nextPrefetchTargetId("BV1x_p1", "bilibili", PlaybackNext.Page(1)))
    }

    @Test
    fun `合集下一集直接用视频 id`() {
        assertEquals(
            "BV2y",
            nextPrefetchTargetId("BV1x", "bilibili", PlaybackNext.Episode(video("BV2y"))),
        )
    }

    @Test
    fun `没有下一集不预取`() {
        assertNull(nextPrefetchTargetId("BV1x", "bilibili", null))
    }

    @Test
    fun `非 bilibili 多P不拼后缀`() {
        assertEquals("yt-video-id", nextPrefetchTargetId("yt-video-id", "youtube", PlaybackNext.Page(0)))
    }
}
