package com.example.mediacenter.data

import android.content.Context
import androidx.core.content.edit

/**
 * 断点续播:持久化最近一次播放位置。
 * 只保存单个视频的位置(videoId -> positionMs),新视频播放时覆盖旧记录,
 * 无需存储多个历史条目,进程重建后能恢复上次观看位置。
 */
object PlaybackPositionStore {

    private const val PREFS = "playback_position"
    private const val KEY_VIDEO_ID = "video_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_SAVED_AT_MS = "saved_at_ms"

    // 距结尾不足 15 秒视为看完,无需续播;不足 30 秒的位置没有恢复价值
    private const val MIN_RESUME_MS = 30_000L
    private const val TAIL_IGNORE_MS = 15_000L
    // 超过 7 天的记录过期,不恢复
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存播放位置。参数非法(太短/为负)时静默忽略。 */
    fun save(context: Context, videoId: String, positionMs: Long) {
        if (positionMs < MIN_RESUME_MS) return
        prefs(context).edit {
            putString(KEY_VIDEO_ID, videoId)
            putLong(KEY_POSITION_MS, positionMs)
            putLong(KEY_SAVED_AT_MS, System.currentTimeMillis())
        }
    }

    /** 返回该视频可恢复的位置;没有记录/不是同一视频/记录过期时返回 0。 */
    fun resumePosition(context: Context, videoId: String, durationMs: Long = 0L): Long {
        val p = prefs(context)
        if (p.getString(KEY_VIDEO_ID, null) != videoId) return 0L
        val position = p.getLong(KEY_POSITION_MS, 0L)
        if (position < MIN_RESUME_MS) return 0L
        if (System.currentTimeMillis() - p.getLong(KEY_SAVED_AT_MS, 0L) > MAX_AGE_MS) return 0L
        // 已接近片尾的观看位置不需要恢复
        if (durationMs > 0 && position >= durationMs - TAIL_IGNORE_MS) return 0L
        return position
    }

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
