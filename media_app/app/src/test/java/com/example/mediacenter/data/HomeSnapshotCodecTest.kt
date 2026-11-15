package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSnapshotCodecTest {
    @Test
    fun `编解码往返保留分类与首屏内容`() {
        val snapshot = HomeSnapshot(
            provider = "bilibili",
            categories = listOf(ContentCategory(id = "movies", title = "电影")),
            selectedTab = "movies",
            items = listOf(VideoSummary(id = "BV1", title = "甲", durationSeconds = 100)),
            total = 25,
            savedAtEpochMs = 123456789L,
        )
        val decoded = HomeSnapshotCodec.decode(HomeSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `空值与非法 JSON 回退 null`() {
        assertNull(HomeSnapshotCodec.decode(null))
        assertNull(HomeSnapshotCodec.decode(""))
        assertNull(HomeSnapshotCodec.decode("not-json"))
    }
}
