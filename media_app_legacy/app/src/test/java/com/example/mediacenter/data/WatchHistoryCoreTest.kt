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

    @Test
    fun upsert_dedupes_and_moves_to_front() {
        val entries = listOf(entry("a"), entry("b"))
        val result = WatchHistoryCore.upsert(entries, entry("b", updatedAt = 9L))
        assertEquals(listOf("b", "a"), result.map { it.videoId })
        assertEquals(9L, result.first().updatedAt)
    }

    @Test
    fun upsert_caps_capacity_evicting_oldest() {
        var entries = listOf(entry("a"), entry("b"))
        entries = WatchHistoryCore.upsert(entries, entry("c"), cap = 2)
        entries = WatchHistoryCore.upsert(entries, entry("d"), cap = 2)
        assertEquals(listOf("d", "c"), entries.map { it.videoId })
    }

    @Test
    fun upsert_rejects_blank_id() {
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.upsert(entries, entry(" ")))
    }

    // ── remove ──

    @Test
    fun remove_removes_matching_entry_and_ignores_others() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val result = WatchHistoryCore.remove(entries, "b")
        assertEquals(listOf("a", "c"), result.map { it.videoId })
        assertEquals(result, WatchHistoryCore.remove(result, " "))
        assertEquals(result, WatchHistoryCore.remove(result, "non_existent"))
    }

    // ── updateProgress ──

    @Test
    fun update_progress_updates_existing_and_moves_to_front() {
        val entries = listOf(entry("a", durationSeconds = 600), entry("b", durationSeconds = 600))
        val result = WatchHistoryCore.updateProgress(entries, "b", positionSeconds = 300, durationSeconds = 600)
        assertEquals(listOf("b", "a"), result.map { it.videoId })
        assertEquals(300L, result.first().positionSeconds)
    }

    @Test
    fun update_progress_records_page() {
        val entries = listOf(entry("BV1xx", durationSeconds = 1800))
        val result = WatchHistoryCore.updateProgress(
            entries, "BV1xx", positionSeconds = 700, durationSeconds = 1800, page = 1,
        )
        assertEquals(1, result.first().page)
        assertEquals(700L, result.first().positionSeconds)
    }

    @Test
    fun update_progress_ignores_short_positions() {
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.updateProgress(entries, "a", positionSeconds = 10, durationSeconds = 600))
    }

    @Test
    fun update_progress_removes_finished_entries() {
        val entries = listOf(entry("a", durationSeconds = 600), entry("b", durationSeconds = 600))
        val result = WatchHistoryCore.updateProgress(entries, "a", positionSeconds = 590, durationSeconds = 600)
        assertEquals(listOf("b"), result.map { it.videoId })
    }

    @Test
    fun update_progress_ignores_unknown_ids() {
        val entries = listOf(entry("a"))
        assertEquals(entries, WatchHistoryCore.updateProgress(entries, "zzz", positionSeconds = 300, durationSeconds = 600))
    }

    @Test
    fun reset_progress_sets_position_and_page_to_zero() {
        val entries = listOf(entry("a", positionSeconds = 500).copy(page = 3), entry("b"))
        val result = WatchHistoryCore.resetProgress(entries, "a")
        val resetEntry = result.first { it.videoId == "a" }
        assertEquals(0L, resetEntry.positionSeconds)
        assertEquals(0, resetEntry.page)
        assertEquals(listOf("a", "b"), result.map { it.videoId })
    }

    // ── encode/decode ──

    @Test
    fun encode_decode_round_trip() {
        val entries = listOf(entry("a", positionSeconds = 120, durationSeconds = 300, updatedAt = 10L))
        val encoded = WatchHistoryCore.encode(entries)
        val decoded = WatchHistoryCore.decode(encoded)
        assertEquals(entries, decoded)
    }

    @Test
    fun decode_handles_corrupted_json() {
        assertTrue(WatchHistoryCore.decode("not a json").isEmpty())
        assertTrue(WatchHistoryCore.decode("").isEmpty())
        assertTrue(WatchHistoryCore.decode(null).isEmpty())
    }

    // ── dropExpired ──

    @Test
    fun drop_expired_drops_stale_entries() {
        val now = 10_000_000L
        val fresh = entry("fresh", updatedAt = now - 1000)
        val stale = entry("stale", updatedAt = now - (8L * 24 * 60 * 60 * 1000))
        val result = WatchHistoryCore.dropExpired(listOf(fresh, stale), nowMs = now)
        assertEquals(listOf("fresh"), result.map { it.videoId })
    }
}
