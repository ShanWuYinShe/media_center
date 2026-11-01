package com.example.mediacenter.ui.search

import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.SearchResponse
import com.example.mediacenter.data.VideoCollectionResponse
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.VideosResponse
import com.example.mediacenter.testutil.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private class FakeRepository(
        override var currentProvider: String = "bilibili",
        var searchHandler: suspend (String, Int, Int) -> SearchResponse = { q, _, _ ->
            SearchResponse(query = q, total = 0, items = emptyList())
        },
        var youtubeHandler: suspend (String) -> SearchResponse = { q ->
            SearchResponse(query = q, total = 0, items = emptyList())
        },
    ) : MediaRepositoryApi {
        override fun setProvider(provider: String) { currentProvider = provider }
        override suspend fun providers(): List<ProviderInfo> = emptyList()
        override suspend fun refreshCredentials() = Unit
        override suspend fun categories(): List<ContentCategory> = emptyList()
        override suspend fun catalogPage(
            categoryId: String, page: Int, pageSize: Int, minDurationSeconds: Long?, maxDurationSeconds: Long?,
        ): VideosResponse = VideosResponse()
        override suspend fun collectionFor(videoId: String): VideoCollectionResponse = VideoCollectionResponse()
        override suspend fun search(query: String, page: Int, pageSize: Int): SearchResponse =
            searchHandler(query, page, pageSize)
        override suspend fun youtubeSearch(query: String): SearchResponse = youtubeHandler(query)
        override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult =
            error("not used")
    }

    private fun video(id: String) = VideoSummary(id = id, title = id)

    private suspend fun TestScope.waitSearch(vm: SearchViewModel, query: String) {
        vm.search(query)
        advanceTimeBy(700)
        advanceUntilIdle()
    }

    @Test
    fun `目录搜索分页与终止条件`() = runTest {
        val pages = mapOf(
            1 to SearchResponse(total = 60, items = (1..30).map { video("v$it") }),
            2 to SearchResponse(total = 60, items = (31..60).map { video("v$it") }),
        )
        val repo = FakeRepository(searchHandler = { _, page, _ -> pages.getValue(page) })
        val vm = SearchViewModel(repo)
        waitSearch(vm, "dota")

        val first = vm.uiState.value
        assertEquals(30, first.results.size)
        assertEquals(60, first.total)
        assertTrue(first.paged)
        assertFalse(first.noMore)
        assertEquals("dota", first.completedQuery)

        vm.loadMore()
        advanceUntilIdle()
        val second = vm.uiState.value
        assertEquals(60, second.results.size)
        assertTrue(second.noMore)
        assertFalse(second.paged)
    }

    @Test
    fun `youtube 实时搜索是单页且不启用分页`() = runTest {
        val repo = FakeRepository(
            currentProvider = "youtube",
            youtubeHandler = { q -> SearchResponse(query = q, total = 2, items = listOf(video("a"), video("b"))) },
        )
        val vm = SearchViewModel(repo)
        waitSearch(vm, "zoo")
        val state = vm.uiState.value
        assertEquals(2, state.results.size)
        assertFalse(state.paged)
        assertTrue(state.noMore)
    }

    @Test
    fun `搜索失败展示错误而不是静默空结果`() = runTest {
        val repo = FakeRepository(searchHandler = { _, _, _ -> throw IOException("boom") })
        val vm = SearchViewModel(repo)
        waitSearch(vm, "dota")
        val state = vm.uiState.value
        assertTrue(state.results.isEmpty())
        assertEquals("boom", state.error)
        assertFalse(state.isSearching)
    }
}
