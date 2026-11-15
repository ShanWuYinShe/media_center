package com.example.mediacenter.util

import android.os.SystemClock
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import com.example.mediacenter.log.AppLog

/**
 * JankStats 帧卡顿观测:每 60s 或页面暂停时汇总一次日志。
 *
 * 只观测不干预(阶段 0 建立现场基线):JankStats 复用系统 FrameMetrics/Choreographer,
 * 采集开销极低;TV 盒子没有 perfetto/adb 时,这是唯一可持续的卡顿数据来源。
 * 后续优化(骨架屏、预取、缩略图)是否真的有效,用这里的 jank 比例复核。
 */
class JankStatsReporter(private val window: Window) {
    private val aggregator = JankAggregator()
    private var lastFlushMs = SystemClock.elapsedRealtime()
    private var stats: JankStats? = null

    fun start() {
        if (stats == null) {
            stats = runCatching {
                JankStats.createAndTrack(window) { frame -> onFrame(frame) }
            }.onFailure {
                AppLog.w("Perf", "JankStats create failed: ${it.message}", it)
            }.getOrNull()
        }
        stats?.isTrackingEnabled = true
    }

    fun stop() {
        stats?.isTrackingEnabled = false
        flush("pause")
    }

    private fun onFrame(frame: FrameData) {
        aggregator.record(frame.frameDurationUiNanos / 1_000_000L, frame.isJank)
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            lastFlushMs = now
            flush("window")
        }
    }

    private fun flush(reason: String) {
        val summary = aggregator.summaryAndReset() ?: return
        AppLog.i("Perf", "jank [$reason]: $summary")
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 60_000L
    }
}
