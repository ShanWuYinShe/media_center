package com.example.mediacenter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JankAggregatorTest {
    @Test
    fun `摘要包含卡顿占比与最长帧`() {
        val aggregator = JankAggregator()
        aggregator.record(frameDurationMs = 16, isJank = false)
        aggregator.record(frameDurationMs = 48, isJank = true)
        aggregator.record(frameDurationMs = 16, isJank = false)

        assertEquals("jank 1/3 frames (33.3%), longest=48ms", aggregator.summaryAndReset())
    }

    @Test
    fun `无帧窗口返回 null 且重置后可继续累计`() {
        val aggregator = JankAggregator()
        assertNull(aggregator.summaryAndReset())

        aggregator.record(frameDurationMs = 32, isJank = true)
        assertEquals("jank 1/1 frames (100.0%), longest=32ms", aggregator.summaryAndReset())
        assertNull(aggregator.summaryAndReset())
    }
}
