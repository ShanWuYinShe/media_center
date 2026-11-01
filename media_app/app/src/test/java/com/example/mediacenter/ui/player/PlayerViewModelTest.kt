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
import com.example.mediacenter.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

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
        override suspend fun collectionFor(videoId: String): VideoCollectionResponse = collection
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
    fun `单P片尾发出合集下一集事件`() = runTest {
        val repo = FakeRepository(playback(0, 0), collection("A", "B"))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()
        assertEquals(PlaybackNext.Episode(VideoSummary(id = "B", title = "B")), vm.uiState.value.next)

        val event = async { vm.episodeEvents.first() }
        // 先让收集器真正订阅(StandardTestDispatcher 不调度就不会订阅),
        // 否则 tryEmit 在无订阅者时会被丢弃。
        advanceUntilIdle()
        assertEquals(true, vm.onPlaybackEnded())
        assertEquals("B", event.await().id)
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
