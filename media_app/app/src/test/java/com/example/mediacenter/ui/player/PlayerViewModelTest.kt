package com.example.mediacenter.ui.player

import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.SearchResponse
import com.example.mediacenter.data.VideoCollectionInfo
import com.example.mediacenter.data.VideoCollectionResponse
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.VideosResponse
import com.example.mediacenter.data.PlaybackPrefetcher
import com.example.mediacenter.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Before
    fun setUp() {
        // 预取器是进程级单例:上一条用例解析过的视频会被下一条命中,导致
        // "同一 VM 解析了几次"的断言失真。每个用例前清空。
        PlaybackPrefetcher.clearForTest()
    }

    private class FakeRepository(
        private val playback: PlaybackResult,
        private val collection: VideoCollectionResponse = VideoCollectionResponse(),
    ) : MediaRepositoryApi {
        override var currentProvider: String = "bilibili"
        val resolvedIds = mutableListOf<String>()

        override fun setProvider(provider: String) { currentProvider = provider }
        override suspend fun providers(): List<ProviderInfo> = emptyList()
        override suspend fun refreshCredentials() = Unit
        override suspend fun categories(): List<ContentCategory> = emptyList()
        override suspend fun catalogPage(
            categoryId: String, page: Int, pageSize: Int, minDurationSeconds: Long?, maxDurationSeconds: Long?,
        ): VideosResponse = VideosResponse()

        // 模拟服务端:合集队列里当前集的下标随请求的 videoId 变化。
        override suspend fun collectionFor(videoId: String): VideoCollectionResponse {
            val items = collection.items.orEmpty()
            return collection.copy(currentIndex = items.indexOfFirst { it.id == videoId })
        }

        override suspend fun search(query: String, page: Int, pageSize: Int): SearchResponse = SearchResponse()
        override suspend fun youtubeSearch(query: String): SearchResponse = SearchResponse()
        override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult {
            resolvedIds += videoId
            return playback
        }
    }

    private fun playback(currentPage: Int, totalPages: Int): PlaybackResult = PlaybackResult(
        options = listOf(
            PlaybackOption(id = "opt", kind = "vod", label = "1080P", url = "https://x/v", qn = 1080),
        ),
        pages = PlaybackPages(currentPage = currentPage, totalPages = totalPages, pageDurations = List(totalPages) { 60 }),
    )

    private fun collection(vararg ids: String): VideoCollectionResponse = VideoCollectionResponse(
        collection = VideoCollectionInfo(id = "season", title = "合集", total = ids.size),
        currentIndex = 0,
        items = ids.map { VideoSummary(id = it, title = it) },
    )

    @Test
    fun `多P优先于合集，片尾切换到下一页`() = runTest {
        val repo = FakeRepository(playback(0, 2), collection("A", "B"))
        val vm = PlayerViewModel("BV1", repository = repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.next is PlaybackNext.Page)

        assertEquals(true, vm.onPlaybackEnded())
        advanceUntilIdle()
        // 第二次解析的是 _p2(第 2 页)。
        assertEquals(listOf("BV1", "BV1_p2"), repo.resolvedIds)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `单P片尾无缝切到合集下一集`() = runTest {
        val repo = FakeRepository(playback(0, 0), collection("A", "B"))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()
        assertEquals("A", vm.uiState.value.videoId)
        assertEquals(PlaybackNext.Episode(VideoSummary(id = "B", title = "B")), vm.uiState.value.next)

        assertEquals(true, vm.onPlaybackEnded())
        advanceUntilIdle()

        // 页内无缝切集:同一个 ViewModel 解析下一集,不重建播放页/播放服务。
        assertEquals("B", vm.uiState.value.videoId)
        assertEquals("B", vm.uiState.value.title)
        assertEquals(listOf("A", "B"), repo.resolvedIds)
        assertNull("B 已是最后一集", vm.uiState.value.next)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `没有下一页或下一集时不连播`() = runTest {
        val repo = FakeRepository(playback(0, 0))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()
        assertNull(vm.uiState.value.next)
        assertEquals(false, vm.onPlaybackEnded())
    }
}
