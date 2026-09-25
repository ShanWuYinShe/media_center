package com.example.mediacenter.ui.player

import androidx.compose.ui.input.key.Key
import androidx.media3.common.PlaybackException

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

internal val CONTROL_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
)

// 焦点在进度条上时左右键的 seek 步长
internal const val SEEK_STEP_MS = 10_000L

internal const val AUTO_HIDE_DELAY_MS = 4000L

// MediaController 连接失败的重试上限与间隔(覆盖服务启动竞态/瞬时故障)
internal const val MAX_CONTROLLER_ATTEMPTS = 3
internal const val CONTROLLER_RETRY_DELAY_MS = 2000L
