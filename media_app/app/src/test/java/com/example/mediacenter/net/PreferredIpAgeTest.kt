package com.example.mediacenter.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredIpAgeTest {
    @Test
    fun `一分钟内显示刚刚测速`() {
        assertEquals("刚刚测速", formatProbeAge(0L))
        assertEquals("刚刚测速", formatProbeAge(59_000L))
    }

    @Test
    fun `分钟级显示分钟数`() {
        assertEquals("1 分钟前测速", formatProbeAge(60_000L))
        assertEquals("5 分钟前测速", formatProbeAge(5 * 60_000L))
        assertEquals("59 分钟前测速", formatProbeAge(59 * 60_000L))
    }

    @Test
    fun `超过一小时按小时显示`() {
        assertEquals("1 小时前测速", formatProbeAge(60 * 60_000L))
        assertEquals("2 小时前测速", formatProbeAge(125 * 60_000L))
    }
}
