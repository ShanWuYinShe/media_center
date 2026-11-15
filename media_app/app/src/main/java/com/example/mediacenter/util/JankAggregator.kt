package com.example.mediacenter.util

/**
 * 帧耗时聚合(纯逻辑,可 JVM 单测):累计总帧/卡顿帧/最长帧,按窗口输出摘要。
 *
 * 卡顿判定沿用 JankStats 的 isJank(基于 FrameMetrics 的 UI 线程 vs 总耗时),
 * 这里只做窗口聚合,避免每帧日志刷屏;低端盒子上日志本身的开销也必须可控。
 */
class JankAggregator {
    private var totalFrames = 0
    private var jankFrames = 0
    private var longestFrameMs = 0L

    val hasFrames: Boolean get() = totalFrames > 0

    fun record(frameDurationMs: Long, isJank: Boolean) {
        totalFrames += 1
        if (frameDurationMs > longestFrameMs) longestFrameMs = frameDurationMs
        if (isJank) jankFrames += 1
    }

    /** 输出当前窗口摘要("卡顿帧/总帧/占比/最长帧"),并重置窗口。无帧时返回 null。 */
    fun summaryAndReset(): String? {
        if (totalFrames == 0) return null
        val frames = totalFrames
        val janks = jankFrames
        val longest = longestFrameMs
        totalFrames = 0
        jankFrames = 0
        longestFrameMs = 0
        val percent = (janks * 1000L / frames).toDouble() / 10.0
        return "jank $janks/$frames frames (${percent}%), longest=${longest}ms"
    }
}
