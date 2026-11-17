package com.example.mediacenter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LegacyPlaybackMediaCache 纯逻辑测试:预取计划/可缓存判定/尾部条件。 */
class LegacyPlaybackMediaCacheTest {
    private val url = "https://upos-sz-mirror.bilivideo.com/video-1-16.mp4?e=sig"

    @Test
    fun `音视频合一且有总长时返回头部加尾部`() {
        val total = 294_810_000L
        val plan = LegacyPlaybackMediaCache.prefetchPlan(url, mimeType = "video/mp4", hasAudioTrack = false, totalBytes = total)
        assertEquals(2, plan.size)
        assertEquals("head", plan[0].tag)
        assertEquals(0L, plan[0].position)
        assertEquals(768L * 1024, plan[0].length)
        assertEquals("tail", plan[1].tag)
        assertEquals(total - 2L * 1024 * 1024, plan[1].position)
        assertEquals(2L * 1024 * 1024, plan[1].length)
    }

    @Test
    fun `音视频分离只预取头部不补尾部`() {
        val plan = LegacyPlaybackMediaCache.prefetchPlan(
            url, mimeType = "video/mp4", hasAudioTrack = true, totalBytes = 294_810_000L)
        assertEquals(1, plan.size)
        assertEquals("head", plan[0].tag)
    }

    @Test
    fun `无总长探测时只返回头部意图`() {
        val plan = LegacyPlaybackMediaCache.prefetchPlan(url, mimeType = "video/mp4", hasAudioTrack = false, totalBytes = null)
        assertEquals(1, plan.size)
        assertEquals("head", plan[0].tag)
    }

    @Test
    fun `总长小于头加尾时不取尾部`() {
        val small = 1L * 1024 * 1024 // 1MB < 768KB + 2MB
        val plan = LegacyPlaybackMediaCache.prefetchPlan(url, mimeType = "video/mp4", hasAudioTrack = false, totalBytes = small)
        assertEquals(1, plan.size)
        assertEquals("head", plan[0].tag)
    }

    @Test
    fun `尾部不超过总长`() {
        val total = 1000L * 1024 // 1000KB > head, 但 < tail
        val plan = LegacyPlaybackMediaCache.prefetchPlan(url, mimeType = "video/mp4", hasAudioTrack = false, totalBytes = total)
        assertEquals(1, plan.size)
    }

    @Test
    fun `HLS 清单不预取`() {
        assertFalse(LegacyPlaybackMediaCache.isStreamCacheable("https://cdn.example.com/master.m3u8"))
        assertFalse(
            LegacyPlaybackMediaCache.isStreamCacheable(
                "https://cdn.example.com/video.m4s", mimeType = "application/x-mpegURL"))
    }

    @Test
    fun `DASH 清单不预取`() {
        assertFalse(LegacyPlaybackMediaCache.isStreamCacheable("https://cdn.example.com/manifest.mpd"))
    }

    @Test
    fun `data 与 blob 不预取`() {
        assertFalse(LegacyPlaybackMediaCache.isStreamCacheable("data:video/mp4;base64,AAAA"))
        assertFalse(LegacyPlaybackMediaCache.isStreamCacheable("blob:https://example.com/uuid"))
    }

    @Test
    fun `普通直链可预取`() {
        assertTrue(LegacyPlaybackMediaCache.isStreamCacheable(url))
        assertTrue(LegacyPlaybackMediaCache.isStreamCacheable("https://cdn.example.com/video.mp4"))
    }

    @Test
    fun `空白 URL 返回空计划`() {
        assertEquals(emptyList<LegacyPlaybackMediaCache.PrefetchSpan>(), LegacyPlaybackMediaCache.prefetchPlan("", "video/mp4", false, 1000L))
        assertEquals(
            emptyList<LegacyPlaybackMediaCache.PrefetchSpan>(),
            LegacyPlaybackMediaCache.prefetchPlan("https://cdn.example.com/v.mp4", "video/mp4", false, 1000L).let { plan ->
                // 空白 URL 也返回空
                LegacyPlaybackMediaCache.prefetchPlan("   ", "video/mp4", false, 1000L)
            }
        )
    }

    @Test
    fun `尾部条件只认音视频合一`() {
        assertTrue(LegacyPlaybackMediaCache.shouldPrefetchTail(hasAudioTrack = false))
        assertFalse(LegacyPlaybackMediaCache.shouldPrefetchTail(hasAudioTrack = true))
    }
}