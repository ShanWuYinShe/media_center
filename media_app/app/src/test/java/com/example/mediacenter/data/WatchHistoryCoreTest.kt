package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryCoreTest {

    private fun entry(
        id: String,
        positionSeconds: Long = 100,
        durationSeconds: Long = 0,
        provider: String = "bilibili",
        updatedAt: Long = 1L,
    ) = WatchEntry(
        videoId = id,
        provider = provider,
        title = "标题$id",
        thumbnailUrl = "https://example.com/$id.jpg",
        durationSeconds = durationSeconds,
        positionSeconds = positionSeconds,
        updatedAt = updatedAt,
    )

    // ── upsert ──

    fun upsert_dedupes_and_moves_to_front() {
        val entries = listOf(entry("a"), entry("b"))
        val result = WatchHistoryCore.upsert(entries, entry("b", updatedAt = 9L))
        assertEquals(listOf("b", "a"), result.map { it.videoId })
        assertEquals(9L, result.first().updatedAt)
    }

    fun upsert_caps_capacity_evicting_oldest() {
        var entries = listOf(entry("a"), entry("b"))
        entries = WatchHistoryCore.upsert(entries, entry("c"), cap = 2)
        entries = WatchHistoryCore.upsert(entries, entry("d"), cap = 2)
        assertEquals(listOf("d", "c"), entries.map { it.videoId })
    }

    fun upsert_rejects_blank_id() {
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.upsert(entries, entry(" ")))
    }

    // ── updateProgress ──

    fun update_progress_updates_existing_and_moves_to_front() {
        val entries = listOf(entry("a", durationSeconds = 600), entry("b", durationSeconds = 600))
        val result = WatchHistoryCore.updateProgress(entries, "b", positionSeconds = 300, durationSeconds = 600)
        assertEquals(listOf("b", "a"), result.map { it.videoId })
        assertEquals(300L, result.first().positionSeconds)
    }

    fun update_progress_ignores_short_positions() {
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.updateProgress(entries, "a", positionSeconds = 10, durationSeconds = 600))
    }

    fun update_progress_removes_finished_entries() {
        val entries = listOf(entry("a", durationSeconds = 600), entry("b", durationSeconds = 600))
        val result = WatchHistoryCore.updateProgress(entries, "a", positionSeconds = 590, durationSeconds = 600)
        assertEquals(listOf("b"), result.map { it.videoId })
    }

    fun update_progress_ignores_unknown_ids() {
        // 元数据由详情页点击播放时登记;未登记的 id 不产生空标题条目
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.updateProgress(entries, "zzz", positionSeconds = 300, durationSeconds = 600))
    }

    // ── encode/decode ──

    fun encode_decode_roundtrip() {
        val entries = listOf(entry("a", positionSeconds = 300, durationSeconds = 600), entry("yt1", provider = "youtube"))
        val raw = WatchHistoryCore.encode(entries)
        assertEquals(entries, WatchHistoryCore.decode(raw))
    }

    fun decode_tolerates_garbage_and_unknown_fields() {
        assertTrue(WatchHistoryCore.decode(null).isEmpty())
        assertTrue(WatchHistoryCore.decode("not-json").isEmpty())
        val decoded = WatchHistoryCore.decode("[{\"videoId\":\"a\",\"futureField\":123}]")
        assertEquals(listOf("a"), decoded.map { it.videoId })
    }
}
