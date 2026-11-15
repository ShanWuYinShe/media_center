package com.example.mediacenter.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPrefetcherTest {
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
    fun `预取结果只能被播放页消费一次`() = runTest {
        val repository = FakeRepository()
        val videoId = "BV1prefetch-once"
        val expected = repository.resolvePlayback(videoId, null)
        PlaybackPrefetcher.put(repository, videoId, null, expected)

        assertEquals(expected, PlaybackPrefetcher.resolve(repository, videoId, null))
        assertNull("消费后应回落为未命中", PlaybackPrefetcher.resolve(repository, videoId, null))
    }

    @Test
    fun `清晰度或视频不匹配时不命中`() = runTest {
        val repository = FakeRepository()
        val videoId = "BV1prefetch-key"
        PlaybackPrefetcher.put(repository, videoId, 80, repository.resolvePlayback(videoId, 80))

        assertNull("qn 不同不应命中", PlaybackPrefetcher.resolve(repository, videoId, null))
        assertNull("视频不同不应命中", PlaybackPrefetcher.resolve(repository, "BV1other", 80))
    }
}
