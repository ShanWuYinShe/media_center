package com.example.mediacenter.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturedHeroTest {
    @Test
    fun `继续观看元信息包含进度与剩余时间`() {
        assertEquals("继续观看 · 已看 50% · 剩余 0:30", continueMeta(30, 60))
    }

    @Test
    fun `时长未知时退化为纯文案`() {
        assertEquals("继续观看", continueMeta(30, 0))
    }

    @Test
    fun `位置超出时长时剩余时间不为负`() {
        assertEquals("继续观看 · 已看 100% · 剩余 0:00", continueMeta(80, 60))
    }
}
