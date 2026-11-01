package com.example.mediacenter.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B 站 durl 多段解析:分段地址必须全部保留(顺序不变),不能只取第一段。
 * 播放器侧由 PlaybackMediaSourceFactory 用 ConcatenatingMediaSource 拼接。
 */
class BilibiliPlaybackOptionsTest {
    private val api = BilibiliApi(client = OkHttpClient())

    private fun payload(vararg urls: String?) = BiliPlayUrlResponse(
        data = BiliPlayUrlResponse.PlayUrlData(
            quality = 80,
            durl = urls.map { BiliPlayUrlResponse.PlayUrlData.Durl(url = it, size = 1024) },
            acceptQuality = listOf(80),
            acceptDescription = listOf("1080P"),
        ),
    )

    @Test
    fun `多段 durl 全部保留且顺序不变`() {
        val (options, _) = api.buildPlaybackOptions(
            "BV1multi",
            0,
            payload("https://cdn/part1.mp4", "https://cdn/part2.mp4", "https://cdn/part3.mp4"),
        )
        val option = options.single()
        assertEquals("https://cdn/part1.mp4", option.url)
        assertEquals(
            listOf("https://cdn/part1.mp4", "https://cdn/part2.mp4", "https://cdn/part3.mp4"),
            option.segments,
        )
    }

    @Test
    fun `单段 durl 不产生 segments`() {
        val (options, _) = api.buildPlaybackOptions("BV1single", 0, payload("https://cdn/only.mp4"))
        assertEquals("https://cdn/only.mp4", options.single().url)
        assertNull(options.single().segments)
    }

    @Test
    fun `空地址与重复地址被剔除`() {
        val (options, _) = api.buildPlaybackOptions(
            "BV1dirty",
            0,
            payload("https://cdn/a.mp4", "", null, "https://cdn/a.mp4", "https://cdn/b.mp4"),
        )
        val option = options.single()
        assertEquals("https://cdn/a.mp4", option.url)
        assertEquals(listOf("https://cdn/a.mp4", "https://cdn/b.mp4"), option.segments)
    }

    @Test
    fun `全部为空时不产生播放选项`() {
        val (options, _) = api.buildPlaybackOptions("BV1empty", 0, payload(null, ""))
        assertEquals(0, options.size)
    }
}
