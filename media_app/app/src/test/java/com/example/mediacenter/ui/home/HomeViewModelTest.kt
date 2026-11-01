package com.example.mediacenter.ui.home

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialLoad_selectsFirstCategoryAndLoadsFirstPage() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("movies", state.selectedTab)
        assertEquals(18, state.items.size)
        assertEquals(25, state.total)
        assertTrue(state.canLoadMore)
        assertEquals(listOf("movies"), repository.pageRequests.map { it.first })
    }

    @Test
    fun selectTab_loadsCategoryAndCachesIt() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        viewModel.selectTab("tv")
        advanceUntilIdle()
        assertEquals("tv", viewModel.uiState.value.selectedTab)
        assertEquals(3, viewModel.uiState.value.items.size)
        assertEquals(1, repository.pageRequests.count { it.first == "tv" })

        // 切回电影应命中 10 分钟缓存，不再发请求。
        val callsBefore = repository.pageRequests.size
        viewModel.selectTab("movies")
        advanceUntilIdle()
        assertEquals("movies", viewModel.uiState.value.selectedTab)
        assertEquals(callsBefore, repository.pageRequests.size)
    }

    @Test
    fun loadMore_appendsUntilTotalReached() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(25, state.items.size)
        assertEquals(25, state.items.distinctBy { it.id }.size)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun selectContinueTab_doesNotRequestNetwork() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()
        val callsBefore = repository.pageRequests.size

        viewModel.selectTab(HomeContinueTabId)
        advanceUntilIdle()

        assertEquals(HomeContinueTabId, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertEquals(callsBefore, repository.pageRequests.size)
    }
}

private class FakeRepository : MediaRepositoryApi {
    override var currentProvider: String = "bilibili"
    val pageRequests = mutableListOf<Pair<String, Int>>()

    override fun setProvider(provider: String) {
        currentProvider = provider
    }

    override suspend fun providers(): List<ProviderInfo> =
        listOf(ProviderInfo("bilibili", "B站"))

    override suspend fun refreshCredentials() = Unit

    override suspend fun categories(): List<ContentCategory> = listOf(
        ContentCategory("movies", "电影"),
        ContentCategory("tv", "电视剧"),
    )

    override suspend fun catalogPage(
        categoryId: String,
        page: Int,
        pageSize: Int,
        minDurationSeconds: Long?,
        maxDurationSeconds: Long?,
    ): VideosResponse {
        pageRequests += categoryId to page
        val total = if (categoryId == "movies") 25 else 3
        val all = (1..total).map {
            VideoSummary(
                id = "${categoryId}_$it",
                title = "$categoryId 第 $it 条",
                durationSeconds = 600L,
            )
        }
        val start = ((page - 1) * pageSize).coerceIn(0, all.size)
        val end = (start + pageSize).coerceAtMost(all.size)
        return VideosResponse(
            page = page,
            pageSize = pageSize,
            total = total,
            items = all.subList(start, end),
        )
    }

    override suspend fun collectionFor(videoId: String): VideoCollectionResponse =
        VideoCollectionResponse()

    override suspend fun search(query: String, page: Int, pageSize: Int): SearchResponse =
        SearchResponse()

    override suspend fun youtubeSearch(query: String): SearchResponse = SearchResponse()

    override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult =
        PlaybackResult(emptyList())
}
