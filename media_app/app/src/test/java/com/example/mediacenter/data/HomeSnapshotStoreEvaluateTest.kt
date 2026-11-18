package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HomeSnapshotStore.evaluate 的 SWR stale/丢弃语义测试(纯逻辑)。 */
class HomeSnapshotStoreEvaluateTest {
    private fun snapshot() = HomeSnapshot(
        provider = "bilibili",
        categories = listOf(ContentCategory(id = "movies", title = "电影")),
        selectedTab = "movies",
        items = listOf(VideoSummary(id = "BV1", title = "甲", durationSeconds = 100)),
        total = 25,
    )

    @Test
    fun `新鲜快照返回且非 stale`() {
        val now = 1_000_000_000_000L
        val r = HomeSnapshotStore.evaluate(now - 60_000L, now, snapshot())
        assertEquals(false, r?.stale)
    }

    @Test
    fun `超 10 分钟标记 stale 但仍返回`() {
        val now = 1_000_000_000_000L
        val r = HomeSnapshotStore.evaluate(now - 11 * 60_000L, now, snapshot())
        assertEquals(true, r?.stale)
    }

    @Test
    fun `超硬上限丢弃`() {
        val now = 1_000_000_000_000L
        assertNull(HomeSnapshotStore.evaluate(now - 31L * 24 * 60 * 60 * 1000L, now, snapshot()))
    }

    @Test
    fun `未来时间视为非法丢弃`() {
        val now = 1_000_000_000_000L
        assertNull(HomeSnapshotStore.evaluate(now + 60_000L, now, snapshot()))
    }
}