package com.example.mediacenter.update

import java.util.Locale

/**
 * 更新下载弹窗的展示文案纯函数:百分比、已下载体积、总体积。
 * 抽成无 Android 依赖的对象,JVM 单测可覆盖 0 / 越界 / GB 级 / 体积未知等边界;
 * 下载进度回调只负责算比例,文案口径集中在这里,normal 与 legacy 保持一致。
 */
internal object UpdateDownloadProgress {
    /** 进度百分比(0f..1f → "0%".."100%"),越界值先收敛。 */
    fun percentText(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

    /**
     * 体积统一按 MB 展示(APK 体量下 KB 无意义),超过 1 GB 换算 GB。
     * 显式 Locale.US:部分设备默认 Locale 的数字分隔符会把 1.5 格式化成 1,5。
     */
    fun formatMegabytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    /**
     * 下载弹窗副标题:"45% · 2.6 MB / 5.8 MB"。totalBytes<=0(服务端未给体积)
     * 时只给百分比,不展示无意义的 0 MB。
     */
    fun summary(progress: Float, totalBytes: Long): String {
        val clamped = progress.coerceIn(0f, 1f)
        val percent = percentText(clamped)
        if (totalBytes <= 0L) return percent
        val copied = (totalBytes * clamped.toDouble()).toLong()
        return "$percent · ${formatMegabytes(copied)} / ${formatMegabytes(totalBytes)}"
    }
}
