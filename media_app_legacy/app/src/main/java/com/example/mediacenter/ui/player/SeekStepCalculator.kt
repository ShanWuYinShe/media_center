package com.example.mediacenter.ui.player

import com.example.mediacenter.ui.components.formatTime

internal data class SeekStepResult(
    val consecutiveCount: Int,
    val direction: Int, // 1: 快进, -1: 快退
    val stepMs: Long,
    val accumulatedDeltaMs: Long,
    val targetPositionMs: Long,
)

internal object SeekStepCalculator {
    const val CONTINUOUS_SEEK_WINDOW_MS = 800L
    const val SEEK_HUD_TIMEOUT_MS = 1000L

    /**
     * 计算连续按键下的快进/快退步长与目标位置。
     * 步长策略：连按 1-2 次 10s，3-5 次 20s，6 次及以上 30s。
     */
    fun calculateStep(
        forward: Boolean,
        nowMs: Long,
        currentPosMs: Long,
        durationMs: Long,
        lastSeekTimeMs: Long,
        lastSeekDirection: Int,
        prevConsecutiveCount: Int,
        prevAccumulatedDeltaMs: Long,
        prevTargetPositionMs: Long = currentPosMs,
    ): SeekStepResult {
        val direction = if (forward) 1 else -1
        val isConsecutive = direction == lastSeekDirection && (nowMs - lastSeekTimeMs) < CONTINUOUS_SEEK_WINDOW_MS
        // 位置锚点与步长档位分开判断:方向反转时档位重新从 1 起算,但只要还在
        // 连续按键窗口内,就必须从上次目标位置继续——播放器 currentPosition 可能
        // 还没追上上一次 seek,用它当锚点会让目标位置被拉回(例如 +20s 后按左
        // 会跳回起点而不是回到 +10s)。
        val hasRecentSeek = prevConsecutiveCount > 0 && (nowMs - lastSeekTimeMs) < CONTINUOUS_SEEK_WINDOW_MS
        val newCount = if (isConsecutive) prevConsecutiveCount + 1 else 1
        val baseAccumulated = if (isConsecutive) prevAccumulatedDeltaMs else 0L

        val stepSeconds = when {
            newCount <= 2 -> 10L
            newCount <= 5 -> 20L
            else -> 30L
        }
        val stepMs = stepSeconds * 1000L
        val delta = if (forward) stepMs else -stepMs
        val newAccumulated = baseAccumulated + delta

        val safeDuration = durationMs.coerceAtLeast(0L)
        // 连按 seek 时以先前的目标位置为锚点累加，避免播放器缓冲中 currentPosition 尚未刷新导致目标位置反复被拉回或步长吃字
        val basePos = if (hasRecentSeek) prevTargetPositionMs.coerceAtLeast(0L) else currentPosMs.coerceAtLeast(0L)
        val targetPos = (basePos + delta).coerceIn(0L, if (safeDuration > 0L) safeDuration else Long.MAX_VALUE)

        return SeekStepResult(
            consecutiveCount = newCount,
            direction = direction,
            stepMs = stepMs,
            accumulatedDeltaMs = newAccumulated,
            targetPositionMs = targetPos,
        )
    }

    fun formatDelta(accumulatedDeltaMs: Long): String {
        val sec = (kotlin.math.abs(accumulatedDeltaMs) / 1000L).coerceAtLeast(1L)
        return if (accumulatedDeltaMs >= 0) "+${sec}秒" else "-${sec}秒"
    }

    fun formatTarget(targetPositionMs: Long, durationMs: Long): String {
        return if (durationMs > 0L) {
            "${formatTime(targetPositionMs)} / ${formatTime(durationMs)}"
        } else {
            formatTime(targetPositionMs)
        }
    }
}
