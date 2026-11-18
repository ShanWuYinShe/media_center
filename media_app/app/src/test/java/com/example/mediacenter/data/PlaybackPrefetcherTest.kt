package com.example.mediacenter.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPrefetcherTest {
    @Before
    fun setUp() {
        // 进程级单例:避免用例间缓存串味。
        PlaybackPrefetcher.clearForTest()
    }

    private class FakeRepository : MediaRepositoryApi {
        override val currentProvider: String = "bilibili"

        override fun setProvider(provider: String) = Unit

        override suspend fun providers(): List<ProviderInfo> = emptyList()

        override suspend fun refreshCredentials() = Unit

        override suspend fun categories(): List<ContentCategory> = emptyList()

        override suspend fun catalogPage(
            categoryId: String,
            page: Int,
            pageSize: Int,
            minDurationSeconds: Long?,
            maxDurationSeconds: Long?,
        ): VideosResponse = VideosResponse()

        override suspend fun collectionFor(videoId: String): VideoCollectionResponse =
            VideoCollectionResponse()

        override suspend fun search(query: String, page: Int, pageSize: Int): SearchResponse =
            SearchResponse()

        override suspend fun youtubeSearch(query: String): SearchResponse = SearchResponse()

        override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult =
            PlaybackResult(
                options = listOf(
                    PlaybackOption(
                        id = "q${qn ?: 0}",
                        kind = "progressive",
                        label = "360P",
                        url = "https://example.test/${videoId}",
                    ),
                ),
            )
    }

    @Test
    fun `TTL 内预取结果可重复复用`() = runTest {
        val repository = FakeRepository()
        val videoId = "BV1prefetch-once"
        val expected = repository.resolvePlayback(videoId, null)
        PlaybackPrefetcher.put(repository, videoId, null, expected)

        assertEquals(expected, PlaybackPrefetcher.resolve(repository, videoId, null))
        assertEquals(
            "快速回看同一视频应继续命中,不再重复解析",
            expected,
            PlaybackPrefetcher.resolve(repository, videoId, null),
        )
    }

    @Test
    fun `清晰度或视频不匹配时不命中`() = runTest {
        val repository = FakeRepository()
        val videoId = "BV1prefetch-key"
        PlaybackPrefetcher.put(repository, videoId, 80, repository.resolvePlayback(videoId, 80))

        assertNull("qn 不同不应命中", PlaybackPrefetcher.resolve(repository, videoId, null))
        assertNull("视频不同不应命中", PlaybackPrefetcher.resolve(repository, "BV1other", 80))
    }

    private fun option(qn: Int) = PlaybackOption(
        id = "q$qn",
        kind = "progressive",
        label = "${qn}P",
        url = "https://example.test/$qn",
        qn = qn,
    )

    private fun multiQualityResult(vararg qns: Int) = PlaybackResult(
        options = qns.map(::option),
        availableQualities = qns.map { QualityOption(it, "${it}P") },
    )

    @Test
    fun `按 qn 查询可命中按自动档位写入的多档预取结果`() = runTest {
        // 多P「下一集」预取用 qn=null(自动选档)写入,播放页 selectPage 却按
        // currentQn 精确查询;没有这层兼容查找,预取永远白做、切页仍要重新解析。
        val repository = FakeRepository()
        val videoId = "BV1prefetch-compatible"
        PlaybackPrefetcher.put(repository, videoId, null, multiQualityResult(2160, 1080, 720))

        val hit = PlaybackPrefetcher.resolve(repository, videoId, 1080)
        assertEquals("应命中同视频的多档结果", 1080, hit?.options?.firstOrNull()?.qn)
        assertEquals("其余档位应保留", 3, hit?.options?.size)
    }

    @Test
    fun `缓存结果没有请求档位时不命中`() = runTest {
        val repository = FakeRepository()
        val videoId = "BV1prefetch-missing-qn"
        PlaybackPrefetcher.put(repository, videoId, null, multiQualityResult(2160, 1440))

        assertNull(
            "结果里没有 720P 时不应拿最高档冒充",
            PlaybackPrefetcher.resolve(repository, videoId, 720),
        )
    }

    @Test
    fun `invalidate 只清目标视频的预取结果`() = runTest {
        val repository = FakeRepository()
        val target = "BV1prefetch-invalidate"
        val other = "BV1prefetch-keep"
        val targetResult = repository.resolvePlayback(target, null)
        val otherResult = repository.resolvePlayback(other, null)
        PlaybackPrefetcher.put(repository, target, null, targetResult)
        PlaybackPrefetcher.put(repository, other, null, otherResult)

        PlaybackPrefetcher.invalidate(repository, target)

        assertNull("失败重试前必须清掉失效直链的预取", PlaybackPrefetcher.resolve(repository, target, null))
        assertEquals("其他视频的预取不受影响", otherResult, PlaybackPrefetcher.resolve(repository, other, null))
    }
}
