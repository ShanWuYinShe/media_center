package com.example.mediacenter.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun `formatDuration 不足 1 小时显示 m_ss`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(3_599_999))
    }

    @Test
    fun `formatDuration 超过 1 小时显示 h_mm_ss`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
        assertEquals("10:00:00", formatDuration(36_000_000))
    }

    @Test
    fun `formatDuration 负数按 0 处理`() {
        assertEquals("0:00", formatDuration(-1))
    }

    @Test
    fun `formatSeconds 以秒为单位格式化`() {
        assertEquals("0:00", formatSeconds(0))
        assertEquals("1:15", formatSeconds(75))
        assertEquals("1:00:00", formatSeconds(3600))
    }
}
