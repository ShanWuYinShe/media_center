package com.example.mediacenter.ui.player

import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepositoryApi
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.QualityOption
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
import org.junit.Assert.assertFalse
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
        val resolvedQns = mutableListOf<Int?>()

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
            resolvedQns += qn
            return playback
        }
    }

    private fun playback(currentPage: Int, totalPages: Int): PlaybackResult = PlaybackResult(
        options = listOf(
            PlaybackOption(id = "opt", kind = "vod", label = "1080P", url = "https://x/v", qn = 1080),
        ),
        pages = PlaybackPages(currentPage = currentPage, totalPages = totalPages, pageDurations = List(totalPages) { 60 }),
        availableQualities = listOf(
            QualityOption(qn = 1080, label = "1080P"),
            QualityOption(qn = 720, label = "720P"),
            QualityOption(qn = 480, label = "480P"),
        ),
    )

    /** YouTube 风格的多档结果(服务端按清晰度降序返回,首档是最高档)。 */
    private fun multiQualityPlayback(): PlaybackResult {
        val qns = listOf(2160, 1080, 720, 480)
        return PlaybackResult(
            options = qns.map { qn ->
                PlaybackOption(id = "q$qn", kind = "vod", label = "${qn}P", url = "https://x/$qn", qn = qn)
            },
            availableQualities = qns.map { QualityOption(qn = it, label = "${it}P") },
        )
    }

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
    fun `连续播放失败时降一档清晰度重试`() = runTest {
        val repo = FakeRepository(playback(0, 0))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()

        assertTrue(vm.fallbackToLowerQuality())
        advanceUntilIdle()

        assertEquals(listOf("A", "A"), repo.resolvedIds)
        // 1080P 起播,失败后降一档到 720P 重新解析。
        assertEquals(listOf(null, 720), repo.resolvedQns)
    }

    @Test
    fun `没有更低清晰度时降级返回 false`() = runTest {
        val repo = FakeRepository(
            PlaybackResult(
                options = listOf(
                    PlaybackOption(id = "q480", kind = "vod", label = "480P", url = "https://x/v", qn = 480),
                ),
                availableQualities = listOf(QualityOption(qn = 480, label = "480P")),
            ),
        )
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()

        assertFalse(vm.fallbackToLowerQuality())
        advanceUntilIdle()
        assertEquals(listOf("A"), repo.resolvedIds)
    }

    @Test
    fun `自动选档按上限收敛且手动切档不受限`() = runTest {
        val repo = FakeRepository(multiQualityPlayback())
        val vm = PlayerViewModel("A", repository = repo, maxInitialQualityQn = 720)
        advanceUntilIdle()

        // 自动选档:最高档 2160 被收敛到 720。
        assertEquals(720, vm.uiState.value.playbackResult?.options?.firstOrNull()?.qn)

        // 手动切档不受上限约束,且全部档位仍在(供菜单继续切换),无需重新解析。
        vm.selectQuality(2160)
        advanceUntilIdle()
        assertEquals(2160, vm.uiState.value.playbackResult?.options?.firstOrNull()?.qn)
        assertEquals(
            setOf(2160, 1080, 720, 480),
            vm.uiState.value.playbackResult?.options?.mapNotNull { it.qn }?.toSet(),
        )
        assertEquals(listOf("A"), repo.resolvedIds)
    }

    @Test
    fun `未知屏幕高度时不收敛自动选档`() = runTest {
        val repo = FakeRepository(multiQualityPlayback())
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()

        assertEquals(2160, vm.uiState.value.playbackResult?.options?.firstOrNull()?.qn)
    }

    @Test
    fun `没有下一页或下一集时不连播`() = runTest {
        val repo = FakeRepository(playback(0, 0))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()
        assertNull(vm.uiState.value.next)
        assertEquals(false, vm.onPlaybackEnded())
    }

    @Test
    fun `播放失败重试强制重新解析而不命中预取缓存`() = runTest {
        val repo = FakeRepository(playback(0, 0))
        val vm = PlayerViewModel("A", repository = repo)
        advanceUntilIdle()
        assertEquals(listOf("A"), repo.resolvedIds)

        // 首次解析结果已回填预取缓存:普通重试直接命中,不再打 repository。
        vm.resolvePlayback()
        advanceUntilIdle()
        assertEquals(listOf("A"), repo.resolvedIds)

        // 播放错误后的重试必须绕过 5 分钟预取缓存:缓存里是同一批失效直链,
        // 不重新解析会让「重试」变成死循环。
        vm.resolvePlayback(forceRefresh = true)
        advanceUntilIdle()
        assertEquals(listOf("A", "A"), repo.resolvedIds)
    }
}
