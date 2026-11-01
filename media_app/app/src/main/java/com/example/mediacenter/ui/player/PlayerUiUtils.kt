package com.example.mediacenter.ui.player

import androidx.media3.common.PlaybackException
import com.example.mediacenter.data.PlaybackResult

internal fun friendlyPlaybackError(error: PlaybackException): String = when {
    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "播放位置已过期，请重试"
    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "播放超时，请重试"
    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的视频格式"
    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
        "播放地址失效或网络异常，请重试"
    error.errorCode in 6001..6010 -> "播放受保护内容失败"
    // 只给数字码:errorCodeName 是英文技术信息,不适合直接展示给 TV 用户
    else -> "播放失败（错误码 ${error.errorCode}）"
}

// 焦点在进度条上时左右键的 seek 步长
internal const val SEEK_STEP_MS = 10_000L

internal const val AUTO_HIDE_DELAY_MS = 4000L

/**
 * 当前播放位置的人类可读标签：
 * - 多P视频优先显示"第 X / Y 集"（与服务端分P一致）；
 * - 单P但属于合集时显示合集进度；
 * - 都不是则返回 null，不在播放页硬凑一个"第 1/1 集"。
 */
internal fun playbackEpisodeLabel(
    result: PlaybackResult,
    collection: PlaybackCollection?,
): String? {
    val pages = result.pages
    if (pages.totalPages > 1) {
        return "第 ${pages.currentPage + 1} / ${pages.totalPages} 集"
    }
    if (collection != null && collection.currentIndex in collection.items.indices) {
        return "第 ${collection.currentIndex + 1} / ${collection.items.size} 集"
    }
    return null
}

// MediaController 连接失败的重试上限与间隔(覆盖服务启动竞态/瞬时故障)
internal const val MAX_CONTROLLER_ATTEMPTS = 3
internal const val CONTROLLER_RETRY_DELAY_MS = 2000L
