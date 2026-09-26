package com.example.mediacenter.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一条观看历史(首页"继续观看"行的数据源)。 */
@Serializable
data class WatchEntry(
    val videoId: String,
    val provider: String,
    val title: String,
    val thumbnailUrl: String,
    val durationSeconds: Long = 0,
    val positionSeconds: Long = 0,
    val updatedAt: Long = 0,
) {
    /** 转成首页卡片所需的 VideoSummary(继续观看行复用 CategoryRow/VideoCard)。 */
    fun toVideoSummary() = VideoSummary(
        id = videoId,
        title = title,
        thumbnailUrl = thumbnailUrl.ifEmpty { null },
        durationSeconds = durationSeconds,
    )
}

/**
 * 观看历史的核心逻辑(纯函数,不依赖 Android,可在 JVM 单测):
 * JSON 数组字符串 <-> 条目列表,新的在前,容量上限淘汰最旧。
 */
object WatchHistoryCore {
    val json = Json { ignoreUnknownKeys = true }
    const val DEFAULT_CAP = 20
    // 与 PlaybackPositionStore 同口径:不足 30s 的位置没有"继续观看"价值
    const val MIN_KEPT_SECONDS = 30L
    // 距结尾不足 15s 视为看完,看完的条目从继续观看中移除
    const val TAIL_IGNORE_SECONDS = 15L

    fun decode(raw: String?): List<WatchEntry> =
        raw?.let { runCatching { json.decodeFromString<List<WatchEntry>>(it) }.getOrNull() } ?: emptyList()

    fun encode(entries: List<WatchEntry>): String = json.encodeToString(entries)

    /** 新条目(或已存在条目的新版本)提到最前,超容量淘汰最旧。 */
    fun upsert(entries: List<WatchEntry>, entry: WatchEntry, cap: Int = DEFAULT_CAP): List<WatchEntry> {
        if (entry.videoId.isBlank()) return entries
        val stamped = if (entry.updatedAt != 0L) entry else entry.copy(updatedAt = System.currentTimeMillis())
        return (listOf(stamped) + entries.filterNot { it.videoId == entry.videoId }).take(cap)
    }

    /**
     * 回写播放进度:只更新已存在的条目(条目创建于详情页点击播放时,元数据齐全),
     * 提到最前;看完(时长已知且距结尾不足 15s)则移除。位置不足 30s 忽略。
     */
    fun updateProgress(
        entries: List<WatchEntry>,
        videoId: String,
        positionSeconds: Long,
        durationSeconds: Long,
    ): List<WatchEntry> {
        if (videoId.isBlank() || positionSeconds < MIN_KEPT_SECONDS) return entries
        val old = entries.firstOrNull { it.videoId == videoId } ?: return entries
        if (durationSeconds > 0 && positionSeconds >= durationSeconds - TAIL_IGNORE_SECONDS) {
            return entries.filterNot { it.videoId == videoId }
        }
        val updated = old.copy(positionSeconds = positionSeconds, updatedAt = System.currentTimeMillis())
        return listOf(updated) + entries.filterNot { it.videoId == videoId }
    }
}

/**
 * 观看历史的 Android 存储(SharedPreferences + JSON)。设备本地状态,不进 catalog,
 * 与合规口径一致:只引用已审核数据的 id,元数据是播放时已在客户端展示过的内容。
 */
object WatchHistory {
    private const val PREFS = "watch_history"
    private const val KEY_ENTRIES = "entries"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(context: Context): List<WatchEntry> =
        WatchHistoryCore.decode(prefs(context).getString(KEY_ENTRIES, null))

    /** 详情页点击播放时登记(元数据齐全,位置先记 0,播放器退出时回写)。 */
    fun upsert(context: Context, entry: WatchEntry) {
        prefs(context).edit {
            putString(
                KEY_ENTRIES,
                WatchHistoryCore.encode(WatchHistoryCore.upsert(entries(context), entry)),
            )
        }
    }

    /** 播放器退出/进度持久化时回写位置。 */
    fun updatePosition(context: Context, videoId: String, positionSeconds: Long, durationSeconds: Long) {
        prefs(context).edit {
            putString(
                KEY_ENTRIES,
                WatchHistoryCore.encode(
                    WatchHistoryCore.updateProgress(entries(context), videoId, positionSeconds, durationSeconds),
                ),
            )
        }
    }

    /** 清除全部观看历史(设置页"清除观看历史")。 */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }
}
