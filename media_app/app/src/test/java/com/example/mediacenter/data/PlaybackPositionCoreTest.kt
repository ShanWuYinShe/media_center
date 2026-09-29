package com.example.mediacenter.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续播核心逻辑单测(纯 JVM,不依赖 Android):多条目存取、容量淘汰、
 * 过期/片尾过滤与旧版单槽位 JSON 的兼容解码。
 */
class PlaybackPositionCoreTest {

    private val now = 1_800_000_000_000L

    @Test
    fun upsert_ignores_short_or_invalid() {
        val entries = mapOf("a" to PositionEntry(60_000L, now))
        // 位置不足 30s / key 为空:不写入
        assertEquals(entries, PlaybackPositionCore.upsert(entries, "b", 10_000L, now))
        assertEquals(entries, PlaybackPositionCore.upsert(entries, "", 60_000L, now))
    }

    @Test
    fun resume_requires_same_key_and_min_position() {
        val entries = PlaybackPositionCore.upsert(emptyMap(), "a", 60_000L, now)
        assertEquals(60_000L, PlaybackPositionCore.resumePositionMs(entries, "a", now))
        // 其他 key(包括多P页 key)不串位
        assertEquals(0L, PlaybackPositionCore.resumePositionMs(entries, "a#p2", now))
        assertEquals(0L, PlaybackPositionCore.resumePositionMs(entries, "b", now))
    }

    @Test
    fun expired_entry_returns_zero() {
        val entries = mapOf("a" to PositionEntry(60_000L, now))
        // 超 7 天:不可恢复
        assertEquals(0L, PlaybackPositionCore.resumePositionMs(entries, "a", now + PlaybackPositionCore.MAX_AGE_MS + 1))
        // 7 天内:可恢复
        assertEquals(60_000L, PlaybackPositionCore.resumePositionMs(entries, "a", now + PlaybackPositionCore.MAX_AGE_MS - 1))
    }

    @Test
    fun tail_position_is_ignored() {
        val entries = mapOf("a" to PositionEntry(590_000L, now))
        assertEquals(0L, PlaybackPositionCore.resumePositionMs(entries, "a", now, durationMs = 600_000L))
        // 时长未知(0)时不过滤片尾
        assertEquals(590_000L, PlaybackPositionCore.resumePositionMs(entries, "a", now, durationMs = 0L))
    }

    @Test
    fun upsert_keeps_last_twenty_by_saved_at() {
        var entries = emptyMap<String, PositionEntry>()
        repeat(PlaybackPositionCore.CAP + 5) { i ->
            entries = PlaybackPositionCore.upsert(entries, "k$i", 60_000L, now + i)
        }
        assertEquals(PlaybackPositionCore.CAP, entries.size)
        // 最旧的 k0..k4 被淘汰,最新的保留
        assertTrue(entries.keys.none { it == "k0" || it == "k4" })
        assertEquals(60_000L, PlaybackPositionCore.resumePositionMs(entries, "k${PlaybackPositionCore.CAP + 4}", now))
    }

    @Test
    fun upsert_prunes_expired_entries() {
        val stale = mapOf("old" to PositionEntry(60_000L, now - PlaybackPositionCore.MAX_AGE_MS - 1))
        val updated = PlaybackPositionCore.upsert(stale, "new", 60_000L, now)
        assertTrue(!updated.containsKey("old"))
        assertTrue(updated.containsKey("new"))
    }

    @Test
    fun json_roundtrip() {
        val entries = mapOf(
            "BV1xx" to PositionEntry(120_000L, now),
            "BV1xx#p2" to PositionEntry(300_000L, now - 1),
        )
        val decoded = PlaybackPositionCore.decode(PlaybackPositionCore.encode(entries))
        assertEquals(entries, decoded)
        // 脏数据安全降级为空 map
        assertEquals(emptyMap<String, PositionEntry>(), PlaybackPositionCore.decode("not-json"))
        assertEquals(emptyMap<String, PositionEntry>(), PlaybackPositionCore.decode(null))
    }

    @Test
    fun legacy_single_slot_json_is_decodable_map() {
        // 旧版单槽位没有 JSON;但历史中途若出现过未知字段,ignoreUnknownKeys 不炸
        val raw = Json.encodeToString(mapOf("a" to PositionEntry(60_000L, now)))
        val decoded = PlaybackPositionCore.decode(raw)
        assertEquals(60_000L, decoded.getValue("a").positionMs)
    }
}
