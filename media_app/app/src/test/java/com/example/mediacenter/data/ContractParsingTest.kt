package com.example.mediacenter.data

import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨端契约测试:用生产 API 抓取的真实响应(fixture,2026-10-13 采集自
 * media.chaoset.com)锁定 App 端解析。服务端字段改名/变形时,这里的
 * 断言会先于线上用户失败。
 *
 * 解析器配置必须与 MediaApi 一致(ignoreUnknownKeys = true):服务端
 * 历史上在 app/update 响应里加过 App 端没有的字段(apkFilename),
 * 严格解析会让整个更新检查崩溃——由 testAppUpdateIgnoresUnknownFields 锁定。
 */
class ContractParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("contract/$name")!!
            .bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun `解析 providers 真实响应`() {
        val body = fixture("providers.json")
        val resp = json.decodeFromString<ProvidersResponse>(body)
        assertEquals(1, resp.items!!.size)
        assertEquals("bilibili", resp.items[0].id)
        assertEquals("B站", resp.items[0].name)
    }

    @Test
    fun `解析 categories 真实响应`() {
        val resp = json.decodeFromString<CategoriesResponse>(fixture("categories.json"))
        assertTrue("分类应有多条", resp.items!!.size > 1)
        val first = resp.items.first()
        assertEquals("dota-zone", first.id)
        assertEquals("Dota 专区", first.title)
        assertTrue(first.description!!.isNotEmpty())
    }

    @Test
    fun `解析分类视频列表真实响应`() {
        val resp = json.decodeFromString<VideosResponse>(fixture("videos.json"))
        assertEquals("dota-zone", resp.category!!.id)
        assertEquals(1, resp.page)
        assertEquals(2, resp.pageSize)
        assertEquals(570, resp.total)
        assertEquals(2, resp.items!!.size)
        val video = resp.items.first()
        // VideoSummary 五字段契约:id 必有,其余可空
        assertTrue(video.id.startsWith("BV"))
        assertTrue(video.title.isNotEmpty())
    }

    @Test
    fun `解析搜索真实响应`() {
        val resp = json.decodeFromString<SearchResponse>(fixture("search.json"))
        assertEquals("dota", resp.query)
        assertEquals(323, resp.total)
        assertEquals(2, resp.items!!.size)
        val video = resp.items.first()
        assertTrue(video.id.isNotEmpty())
        // 搜索结果带缩略图 URL(B 站 CDN)
        assertTrue(video.thumbnailUrl!!.startsWith("https://"))
    }

    @Test
    fun `解析应用更新真实响应`() {
        val info = json.decodeFromString<AppUpdateInfo>(fixture("update.json"))
        assertEquals(110000, info.versionCode)
        assertEquals("1.10.0", info.versionName)
        assertTrue(info.apkUrl.startsWith("https://media.chaoset.com/"))
        assertTrue(info.sizeBytes > 0)
        assertEquals(64, info.sha256.length)
        assertTrue(info.releaseNotes!!.isNotEmpty())
    }

    @Test
    fun `更新响应含 App 未定义字段时不崩溃`() {
        // 服务端实际返回 apkFilename(见 update.json),App 模型没有该字段。
        // 若解析器改成严格模式,更新检查会整体崩溃——此测试锁定宽容行为。
        val body = fixture("update.json")
        assertTrue("fixture 必须包含 App 未定义的 apkFilename 字段", body.contains("\"apkFilename\""))
        val info = json.decodeFromString<AppUpdateInfo>(body)
        assertEquals(110000, info.versionCode)
    }

    @Test
    fun `可选字段缺失时使用默认值`() {
        // 最小合法响应:只给必填字段
        val minimal = "{\"versionCode\":2,\"versionName\":\"0.0.2\",\"apkUrl\":\"https://x/a.apk\",\"sizeBytes\":1,\"sha256\":\"ab\"}"
        val info = json.decodeFromString<AppUpdateInfo>(minimal)
        assertNull(info.releaseNotes)
        assertNull(info.publishedAt)
        // VideoSummary 缺可选字段
        val video = json.decodeFromString<VideoSummary>("{\"id\":\"BV1x\",\"title\":\"t\"}")
        assertNull(video.description)
        assertNull(video.thumbnailUrl)
        assertNull(video.durationSeconds)
    }

    @Test
    fun `解析 youtube resolve 响应并转换为播放结果`() {
        val resp = json.decodeFromString<YouTubeResolveResponse>(fixture("youtube_resolve.json"))
        assertEquals(900L, resp.durationSeconds)
        assertEquals(2, resp.options.size)
        assertEquals(2, resp.availableQualities.size)
        // 档位顺序保持服务端给的顺序(服务端已按优选排序,App 不重排)
        assertEquals("yt-720-progressive", resp.options[0].id)
        assertEquals(1080, resp.options[1].qn)
        val result = resp.toPlaybackResult()
        assertEquals(2, result.options.size)
        assertEquals(2, result.availableQualities.size)
        // YouTube 无多P,pages 保持默认单页
        assertEquals(0, result.pages.currentPage)
        assertEquals(0, result.pages.totalPages)
    }

    @Test
    fun `youtube resolve 空选项时抛 IO 异常`() {
        val resp = json.decodeFromString<YouTubeResolveResponse>(
            "{\"options\":[],\"availableQualities\":[],\"durationSeconds\":0}",
        )
        val error = runCatching { resp.toPlaybackResult() }.exceptionOrNull()
        assertTrue("应为 IOException", error is IOException)
        assertEquals("YouTube 解析结果为空", error!!.message)
    }

    @Test
    fun `youtube resolve 缺 durationSeconds 时默认 0`() {
        // durationSeconds 在服务端是 omitempty,可能缺省
        val resp = json.decodeFromString<YouTubeResolveResponse>(
            "{\"options\":[{\"id\":\"a\",\"kind\":\"progressive\",\"label\":\"720p\",\"url\":\"https://x/v\",\"headers\":{}}],\"availableQualities\":[]}",
        )
        assertEquals(0L, resp.durationSeconds)
        // headers 缺省时为空 map 而非 null
        assertTrue(resp.options[0].headers.isEmpty())
    }
}
