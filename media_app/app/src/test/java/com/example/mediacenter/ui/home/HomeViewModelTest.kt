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
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // 当前分类 movies 加载完成后会预热相邻分类 tv(后台预热不影响当前页)。
        assertEquals(listOf("movies", "tv"), repository.pageRequests.map { it.first })
    }

    @Test
    fun initialLoad_prefetchNeighbor_thenSwitchHitsCache() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        // 相邻分类 tv 已预热(只发一次请求)。
        assertEquals(1, repository.pageRequests.count { it.first == "tv" })

        viewModel.selectTab("tv")
        advanceUntilIdle()

        assertEquals("tv", viewModel.uiState.value.selectedTab)
        assertEquals(3, viewModel.uiState.value.items.size)
        // 切到预热过的分类直接命中缓存,不再发第二次请求。
        assertEquals(1, repository.pageRequests.count { it.first == "tv" })
    }

    @Test
    fun youtubeProvider_skipsAdjacentPrefetch() = runTest {
        val repository = FakeRepository()
        repository.currentProvider = "youtube"
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        // youtube 分类是实时 ytsearch:不替用户预取相邻分类,只请求当前分类。
        assertEquals(listOf("movies"), repository.pageRequests.map { it.first })

        viewModel.selectTab("tv")
        advanceUntilIdle()
        assertEquals(2, repository.pageRequests.size)
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

    @Test
    fun selectTab_expiredCache_showsStaleItemsWhileRefreshing() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        val stale = List(2) { VideoSummary(id = "stale_$it", title = "旧内容 $it") }
        viewModel.seedCategoryCacheForTest("tv", stale, ageMs = 11 * 60 * 1000L)
        repository.gateTvRequest = CompletableDeferred()

        viewModel.selectTab("tv")
        advanceUntilIdle()

        // 刷新尚未返回:先把过期缓存渲染出来(不闪骨架),并拦住 loadMore 抢跑。
        assertEquals("tv", viewModel.uiState.value.selectedTab)
        assertEquals(stale.map { it.id }, viewModel.uiState.value.items.map { it.id })
        assertTrue(viewModel.uiState.value.isLoading)

        repository.gateTvRequest?.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.items.size)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse("刷新成功不应留下失败标记", viewModel.uiState.value.staleRefreshFailed)
    }

    @Test
    fun selectTab_refreshFailure_keepsStaleItems() = runTest {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(repository, autoCheckUpdate = false)
        advanceUntilIdle()

        val stale = List(2) { VideoSummary(id = "stale_$it", title = "旧内容 $it") }
        viewModel.seedCategoryCacheForTest("tv", stale, ageMs = 11 * 60 * 1000L)
        repository.gateTvRequest = CompletableDeferred()

        viewModel.selectTab("tv")
        advanceUntilIdle()
        assertEquals(stale.map { it.id }, viewModel.uiState.value.items.map { it.id })

        repository.gateTvRequest?.completeExceptionally(IOException("network down"))
        advanceUntilIdle()

        assertEquals(stale.map { it.id }, viewModel.uiState.value.items.map { it.id })
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        // 后台刷新失败要留标记:网络恢复时 HomeScreen 据此自动重试,
        // 旧实现只记日志,用户会长时间无感地看过期内容。
        assertTrue("已有内容时刷新失败应标记 staleRefreshFailed", viewModel.uiState.value.staleRefreshFailed)

        // 网络恢复/手动 retry 成功后标记清空。
        repository.gateTvRequest = null
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.items.size)
        assertFalse("重试成功后标记应清空", viewModel.uiState.value.staleRefreshFailed)
    }
}

private class FakeRepository : MediaRepositoryApi {
    override var currentProvider: String = "bilibili"
    val pageRequests = mutableListOf<Pair<String, Int>>()

    /** 非空时 tv 分类请求会挂起,模拟"后台刷新在途"以验证 SWR 行为。 */
    var gateTvRequest: CompletableDeferred<Unit>? = null

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
        if (categoryId == "tv") gateTvRequest?.await()
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
