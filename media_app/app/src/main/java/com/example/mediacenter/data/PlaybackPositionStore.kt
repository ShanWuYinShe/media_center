package com.example.mediacenter.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 单个存储 key(单P视频 videoId 或多P的 "videoId#pN")的断点记录。 */
@Serializable
data class PositionEntry(
    val positionMs: Long,
    val savedAtMs: Long,
)

/**
 * 断点续播的核心逻辑(纯函数,不依赖 Android,可在 JVM 单测):
 * JSON 对象字符串 <-> key->记录 映射,容量上限淘汰最旧,过期惰性清除。
 *
 * 历史版本是单槽位(全进程只存最后一条),与观看历史/继续观看行的多条目展示
 * 不匹配:任何非最近观看的视频都会"详情页提示续播、播放却从头开始"。改为按
 * [playbackStorageKey] 逐条存储,容量与 [WatchHistoryCore.DEFAULT_CAP] 对齐:
 * 继续观看行能展示多少条,断点就能恢复多少条。
 */
object PlaybackPositionCore {
    val json = Json { ignoreUnknownKeys = true }
    // 与 WatchHistoryCore.DEFAULT_CAP 同口径
    const val CAP = 20
    // 不足 30s 的位置没有恢复价值
    const val MIN_RESUME_MS = 30_000L
    // 距结尾不足 15s 视为看完,无需续播
    const val TAIL_IGNORE_MS = 15_000L
    // 超过 7 天的记录过期,不恢复(与 WatchHistoryCore.DEFAULT_MAX_AGE_MS 同口径)
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun decode(raw: String?): Map<String, PositionEntry> =
        raw?.let { runCatching { json.decodeFromString<Map<String, PositionEntry>>(it) }.getOrNull() } ?: emptyMap()

    fun encode(entries: Map<String, PositionEntry>): String = json.encodeToString(entries)

    /** 写入/刷新一条断点;顺带惰性清除过期条目,超容量按保存时间淘汰最旧。 */
    fun upsert(
        entries: Map<String, PositionEntry>,
        key: String,
        positionMs: Long,
        nowMs: Long,
    ): Map<String, PositionEntry> {
        if (key.isBlank() || positionMs < MIN_RESUME_MS) return entries
        val updated = (entries + (key to PositionEntry(positionMs, nowMs)))
            .filterValues { nowMs - it.savedAtMs <= MAX_AGE_MS }
        if (updated.size <= CAP) return updated
        return updated.entries
            .sortedByDescending { it.value.savedAtMs }
            .take(CAP)
            .associate { it.toPair() }
    }

    /** 返回该 key 可恢复的位置;没有记录/已过期/太短/接近片尾时返回 0。 */
    fun resumePositionMs(
        entries: Map<String, PositionEntry>,
        key: String,
        nowMs: Long,
        durationMs: Long = 0L,
    ): Long {
        val entry = entries[key] ?: return 0L
        if (nowMs - entry.savedAtMs > MAX_AGE_MS) return 0L
        if (entry.positionMs < MIN_RESUME_MS) return 0L
        // 已接近片尾的观看位置不需要恢复
        if (durationMs > 0 && entry.positionMs >= durationMs - TAIL_IGNORE_MS) return 0L
        return entry.positionMs
    }
}

/**
 * 断点续播:按存储 key 持久化最近一次播放位置(SharedPreferences + JSON)。
 * key 由调用方经 [playbackStorageKey] 组合,保存与恢复必须使用同一结果;
 * 记录条数上限与过期口径见 [PlaybackPositionCore]。
 */
object PlaybackPositionStore {

    private const val PREFS = "playback_position"
    private const val KEY_ENTRIES = "entries"
    // 旧版单槽位字段,仅在迁移读取时使用
    private const val KEY_LEGACY_VIDEO_ID = "video_id"
    private const val KEY_LEGACY_POSITION_MS = "position_ms"
    private const val KEY_LEGACY_SAVED_AT_MS = "saved_at_ms"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取全部断点;首次访问时把旧版单槽位记录迁移进多条目格式。 */
    fun entries(context: Context): Map<String, PositionEntry> {
        val p = prefs(context)
        val raw = p.getString(KEY_ENTRIES, null)
        if (raw != null) return PlaybackPositionCore.decode(raw)
        val legacyId = p.getString(KEY_LEGACY_VIDEO_ID, null) ?: return emptyMap()
        // 旧版 video_id 字段存的就是 playbackStorageKey 的组合形态,可直接沿用
        val migrated = mapOf(
            legacyId to PositionEntry(
                positionMs = p.getLong(KEY_LEGACY_POSITION_MS, 0L),
                savedAtMs = p.getLong(KEY_LEGACY_SAVED_AT_MS, 0L),
            ),
        )
        p.edit {
            putString(KEY_ENTRIES, PlaybackPositionCore.encode(migrated))
            remove(KEY_LEGACY_VIDEO_ID)
            remove(KEY_LEGACY_POSITION_MS)
            remove(KEY_LEGACY_SAVED_AT_MS)
        }
        return migrated
    }

    /** 保存播放位置。参数非法(太短/为负)时静默忽略。 */
    fun save(context: Context, videoId: String, positionMs: Long) {
        prefs(context).edit {
            putString(
                KEY_ENTRIES,
                PlaybackPositionCore.encode(
                    PlaybackPositionCore.upsert(entries(context), videoId, positionMs, System.currentTimeMillis()),
                ),
            )
        }
    }

    /** 返回该视频可恢复的位置;没有记录/记录过期时返回 0。 */
    fun resumePosition(context: Context, videoId: String, durationMs: Long = 0L): Long =
        PlaybackPositionCore.resumePositionMs(entries(context), videoId, System.currentTimeMillis(), durationMs)

    /** 清除全部断点续播记录(设置页"清除数据")。 */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }
}

/**
 * 分页视频的存储 key:多P按页隔离位置("BV1xx#p2" = 第 2 页),单P与多P首页
 * 保持原 videoId——历史记录无需迁移。key 由调用方组合,保存与恢复必须
 * 使用同一 [playbackStorageKey] 结果。
 */
fun playbackStorageKey(videoId: String, page: Int): String =
    if (page > 0) "$videoId#p${page + 1}" else videoId
