package com.example.mediacenter.util

import java.util.Locale

// 播放器/卡片/详情页共用的时长格式化:小于 1 小时显示 m:ss,否则 h:mm:ss。
// 显式 Locale:避免设备默认 Locale 改变数字分隔符导致界面时长显示异常。
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

// 直接以秒为单位格式化(如目录里的 durationSeconds 字段)。
fun formatSeconds(seconds: Long): String = formatDuration(seconds * 1000)
