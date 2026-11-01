package com.example.mediacenter.ui.player

import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.VideoCollectionInfo
import com.example.mediacenter.data.VideoCollectionResponse
import com.example.mediacenter.data.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackQueueTest {
    private fun video(id: String) = VideoSummary(id = id, title = id)

    @Test
    fun `多P下一页只在还有后续页时返回`() {
        assertEquals(1, nextPageIndex(PlaybackPages(currentPage = 0, totalPages = 3)))
        assertEquals(2, nextPageIndex(PlaybackPages(currentPage = 1, totalPages = 3)))
        assertNull(nextPageIndex(PlaybackPages(currentPage = 2, totalPages = 3)))
        assertNull(nextPageIndex(PlaybackPages(currentPage = 0, totalPages = 0))) // YouTube 单视频
        assertNull(nextPageIndex(PlaybackPages(currentPage = 0, totalPages = 1)))
    }

    @Test
    fun `合集下一个只看当前下标之后`() {
        val collection = PlaybackCollection(
            id = "season",
            title = "合集",
            items = listOf(video("A"), video("B"), video("C")),
            currentIndex = 0,
        )
        assertEquals("B", collection.next()?.id)
        assertEquals("C", collection.copy(currentIndex = 1).next()?.id)
        assertNull(collection.copy(currentIndex = 2).next())
        assertNull(collection.copy(currentIndex = -1).next())
    }

    @Test
    fun `自动连播优先多P下一页，其次合集下一集`() {
        val multiPage = PlaybackResult(
            options = emptyList(),
            pages = PlaybackPages(currentPage = 0, totalPages = 2),
        )
        val collection = PlaybackCollection(
            id = "s", title = "合集", items = listOf(video("A"), video("B")), currentIndex = 0,
        )
        assertEquals(PlaybackNext.Page(1), nextPlayback(multiPage, collection))

        val lastPage = multiPage.copy(pages = PlaybackPages(currentPage = 1, totalPages = 2))
        assertEquals(PlaybackNext.Episode(video("B")), nextPlayback(lastPage, collection))

        assertNull(nextPlayback(null, null))
    }

    @Test
    fun `响应转队列时过滤无合集与空队列`() {
        assertNull(playbackCollectionFrom(VideoCollectionResponse(collection = null)))

        val empty = VideoCollectionResponse(
            collection = VideoCollectionInfo(id = "s", title = "空", total = 0),
            currentIndex = -1,
            items = emptyList(),
        )
        assertNull(playbackCollectionFrom(empty))

        val ok = VideoCollectionResponse(
            collection = VideoCollectionInfo(id = "s", title = "合集", total = 2),
            currentIndex = 0,
            items = listOf(video("A"), video("B")),
        )
        val queue = playbackCollectionFrom(ok)!!
        assertEquals("s", queue.id)
        assertEquals(0, queue.currentIndex)
        assertEquals("B", queue.next()?.id)
    }

    @Test
    fun `越界下标按不在队列处理，避免跳到错误视频`() {
        val response = VideoCollectionResponse(
            collection = VideoCollectionInfo(id = "s", title = "合集", total = 2),
            currentIndex = 99,
            items = listOf(video("A"), video("B")),
        )
        val queue = playbackCollectionFrom(response)!!
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.next())
    }
}
