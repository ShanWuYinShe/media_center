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
import org.junit.Assert.assertNull
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
        var bilibiliHandler: suspend (String, Int) -> SearchResponse = { q, _ ->
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
        override suspend fun bilibiliSearch(query: String, page: Int): SearchResponse =
            bilibiliHandler(query, page)
        override suspend fun resolvePlayback(videoId: String, qn: Int?): PlaybackResult =
            error("not used")
    }

    private fun video(id: String) = VideoSummary(id = id, title = id)

    private suspend fun TestScope.waitSearch(vm: SearchViewModel, query: String) {
        vm.submit(query)
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
    fun `输入只更新文本不触发搜索`() = runTest {
        var searchCalls = 0
        val repo = FakeRepository(searchHandler = { q, _, _ ->
            searchCalls++
            SearchResponse(query = q, total = 1, items = listOf(video("v1")))
        })
        val vm = SearchViewModel(repo)
        // 输入几个字:不提交就不应发请求(TV 上边输入边搜是纯浪费)。
        vm.onQueryChange("d")
        vm.onQueryChange("do")
        vm.onQueryChange("dot")
        vm.onQueryChange("dota")
        advanceUntilIdle()
        assertEquals(0, searchCalls)
        assertEquals("dota", vm.uiState.value.query)
        assertTrue(vm.uiState.value.results.isEmpty())

        // 显式提交才真正搜索。
        vm.submit()
        assertTrue(vm.uiState.value.isSearching)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSearching)
        assertEquals(1, searchCalls)
        assertEquals(1, vm.uiState.value.results.size)
    }

    @Test
    fun `搜索失败后同一关键词重试会再次发起请求`() = runTest {
        var attempts = 0
        val repo = FakeRepository(searchHandler = { q, _, _ ->
            attempts++
            if (attempts == 1) throw IOException("boom")
            SearchResponse(query = q, total = 1, items = listOf(video("v1")))
        })
        val vm = SearchViewModel(repo)
        waitSearch(vm, "dota")
        assertEquals("boom", vm.uiState.value.error)
        assertEquals(1, attempts)

        // 重试按钮与重复点同一个热词都走这里:query 相同,queryFlow 不会发射,
        // 必须显式重跑,否则此前是一次静默 no-op。
        vm.submit()
        assertTrue(vm.uiState.value.isSearching)
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.results.size)
    }

    @Test
    fun `bilibili 走全站搜索且可分页`() = runTest {
        val pages = mapOf(
            1 to SearchResponse(query = "黑神话", total = 45, items = (1..20).map { video("b$it") }),
            2 to SearchResponse(query = "黑神话", total = 45, items = (21..40).map { video("b$it") }),
            3 to SearchResponse(query = "黑神话", total = 45, items = listOf(video("b41"))),
        )
        var bilibiliCalls = 0
        val repo = FakeRepository(
            currentProvider = "bilibili",
            bilibiliHandler = { q, page ->
                bilibiliCalls++
                pages.getValue(page)
            },
        )
        val vm = SearchViewModel(repo)
        waitSearch(vm, "黑神话")

        val first = vm.uiState.value
        assertEquals(20, first.results.size)
        assertEquals(45, first.total)
        assertTrue(first.paged)
        assertFalse(first.noMore)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(40, vm.uiState.value.results.size)

        vm.loadMore()
        advanceUntilIdle()
        val last = vm.uiState.value
        assertEquals(41, last.results.size)
        assertTrue(last.noMore)
        assertFalse(last.paged)
        assertEquals(3, bilibiliCalls)
    }

    @Test
    fun `不足两字符提交不发请求`() = runTest {
        var searchCalls = 0
        val repo = FakeRepository(searchHandler = { _, _, _ ->
            searchCalls++
            SearchResponse()
        })
        val vm = SearchViewModel(repo)
        vm.onQueryChange("a")
        vm.submit()
        advanceUntilIdle()
        assertEquals(0, searchCalls)
        assertEquals("a", vm.uiState.value.query)
        assertNull(vm.uiState.value.completedQuery)
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

    @Test
    fun `目录搜索超出总预算时给出超时文案`() = runTest {
        val repo = FakeRepository(searchHandler = { _, _, _ ->
            kotlinx.coroutines.delay(60_000)
            SearchResponse()
        })
        val vm = SearchViewModel(repo)
        waitSearch(vm, "dota")
        val state = vm.uiState.value
        assertFalse(state.isSearching)
        assertEquals("搜索超时，请检查网络后重试", state.error)
    }
}