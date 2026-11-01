package com.example.mediacenter.ui.category

import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.SearchResponse
import com.example.mediacenter.data.VideoCollectionResponse
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.VideosResponse
import com.example.mediacenter.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryVideosViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private class FakeRepository(
        override var currentProvider: String,
        private val response: VideosResponse,
    ) : MediaRepositoryApi {
        var catalogCalls = 0
        override fun setProvider(provider: String) { currentProvider = provider }
        override suspend fun providers(): List<ProviderInfo> = emptyList()
        override suspend fun refreshCredentials() = Unit
        override suspend fun categories(): List<ContentCategory> = emptyList()
        override suspend fun catalogPage(
            categoryId: String,
            page: Int,
            pageSize: Int,
            minDurationSeconds: Long?,
            maxDurationSeconds: Long?,
        ): VideosResponse {
            catalogCalls++
            return response
        }
        override suspend fun collectionFor(videoId: String): VideoCollectionResponse = VideoCollectionResponse()
        override suspend fun search(query: String, page: Int, pageSize: Int): SearchResponse = SearchResponse()
        override suspend fun youtubeSearch(query: String): SearchResponse = SearchResponse()
        override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult = error("not used")
    }

    private fun videos(count: Int) = (1..count).map { VideoSummary(id = "yt$it", title = "视频 $it") }

    @Test
    fun `YouTube 分类是单页结果且不触发翻页`() = runTest {
        val repo = FakeRepository(
            currentProvider = "youtube",
            response = VideosResponse(
                category = ContentCategory(id = "youtube-music", title = "音乐"),
                page = 1,
                pageSize = 20,
                total = 20,
                items = videos(20),
            ),
        )
        val vm = CategoryVideosViewModel(repo)
        vm.load("youtube-music", "音乐")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(20, state.items.size)
        assertTrue(state.noMore)
        assertEquals(1, repo.catalogCalls)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals("YouTube 分类不应再请求下一页", 1, repo.catalogCalls)
    }
}
