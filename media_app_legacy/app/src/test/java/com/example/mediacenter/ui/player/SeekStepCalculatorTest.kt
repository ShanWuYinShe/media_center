package com.example.mediacenter.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepCalculatorTest {

    @Test
    fun `single forward seek uses 10s step`() {
        val res = SeekStepCalculator.calculateStep(
            forward = true,
            nowMs = 1000L,
            currentPosMs = 30_000L,
            durationMs = 120_000L,
            lastSeekTimeMs = 0L,
            lastSeekDirection = 0,
            prevConsecutiveCount = 0,
            prevAccumulatedDeltaMs = 0L,
        )
        assertEquals(1, res.consecutiveCount)
        assertEquals(1, res.direction)
        assertEquals(10_000L, res.stepMs)
        assertEquals(10_000L, res.accumulatedDeltaMs)
        assertEquals(40_000L, res.targetPositionMs)
        assertEquals("+10秒", SeekStepCalculator.formatDelta(res.accumulatedDeltaMs))
    }

    @Test
    fun `single rewind seek uses 10s step and clamps at zero`() {
        val res = SeekStepCalculator.calculateStep(
            forward = false,
            nowMs = 1000L,
            currentPosMs = 5_000L,
            durationMs = 120_000L,
            lastSeekTimeMs = 0L,
            lastSeekDirection = 0,
            prevConsecutiveCount = 0,
            prevAccumulatedDeltaMs = 0L,
        )
        assertEquals(1, res.consecutiveCount)
        assertEquals(-1, res.direction)
        assertEquals(10_000L, res.stepMs)
        assertEquals(-10_000L, res.accumulatedDeltaMs)
        assertEquals(0L, res.targetPositionMs) // clamped at 0
        assertEquals("-10秒", SeekStepCalculator.formatDelta(res.accumulatedDeltaMs))
    }

    @Test
    fun `consecutive forward seeks increase step up to 30s`() {
        val r1 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 1000L, currentPosMs = 0L, durationMs = 300_000L,
            lastSeekTimeMs = 0L, lastSeekDirection = 0, prevConsecutiveCount = 0, prevAccumulatedDeltaMs = 0L,
        )
        assertEquals(1, r1.consecutiveCount)

        val r2 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 1300L, currentPosMs = r1.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 1000L, lastSeekDirection = 1, prevConsecutiveCount = r1.consecutiveCount, prevAccumulatedDeltaMs = r1.accumulatedDeltaMs,
        )
        assertEquals(2, r2.consecutiveCount)
        assertEquals(10_000L, r2.stepMs)
        assertEquals(20_000L, r2.accumulatedDeltaMs)

        val r3 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 1600L, currentPosMs = r2.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 1300L, lastSeekDirection = 1, prevConsecutiveCount = r2.consecutiveCount, prevAccumulatedDeltaMs = r2.accumulatedDeltaMs,
        )
        assertEquals(3, r3.consecutiveCount)
        assertEquals(20_000L, r3.stepMs)
        assertEquals(40_000L, r3.accumulatedDeltaMs)

        val r4 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 1900L, currentPosMs = r3.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 1600L, lastSeekDirection = 1, prevConsecutiveCount = r3.consecutiveCount, prevAccumulatedDeltaMs = r3.accumulatedDeltaMs,
        )
        assertEquals(4, r4.consecutiveCount)
        assertEquals(20_000L, r4.stepMs)
        assertEquals(60_000L, r4.accumulatedDeltaMs)

        val r5 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 2200L, currentPosMs = r4.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 1900L, lastSeekDirection = 1, prevConsecutiveCount = r4.consecutiveCount, prevAccumulatedDeltaMs = r4.accumulatedDeltaMs,
        )
        assertEquals(5, r5.consecutiveCount)
        assertEquals(20_000L, r5.stepMs)
        assertEquals(80_000L, r5.accumulatedDeltaMs)

        val r6 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 2500L, currentPosMs = r5.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 2200L, lastSeekDirection = 1, prevConsecutiveCount = r5.consecutiveCount, prevAccumulatedDeltaMs = r5.accumulatedDeltaMs,
        )
        assertEquals(6, r6.consecutiveCount)
        assertEquals(30_000L, r6.stepMs)
        assertEquals(110_000L, r6.accumulatedDeltaMs)
    }

    @Test
    fun `reversing direction resets consecutive count and delta`() {
        val r1 = SeekStepCalculator.calculateStep(
            forward = true, nowMs = 1000L, currentPosMs = 50_000L, durationMs = 300_000L,
            lastSeekTimeMs = 0L, lastSeekDirection = 0, prevConsecutiveCount = 0, prevAccumulatedDeltaMs = 0L,
        )
        val r2 = SeekStepCalculator.calculateStep(
            forward = false, nowMs = 1200L, currentPosMs = r1.targetPositionMs, durationMs = 300_000L,
            lastSeekTimeMs = 1000L, lastSeekDirection = 1, prevConsecutiveCount = 1, prevAccumulatedDeltaMs = 10_000L,
        )
        assertEquals(1, r2.consecutiveCount)
        assertEquals(-1, r2.direction)
        assertEquals(-10_000L, r2.accumulatedDeltaMs)
        assertEquals(50_000L, r2.targetPositionMs)
    }

    @Test
    fun `formatTarget formats properly with duration`() {
        val text = SeekStepCalculator.formatTarget(65_000L, 125_000L)
        assertEquals("1:05 / 2:05", text)
    }
}
