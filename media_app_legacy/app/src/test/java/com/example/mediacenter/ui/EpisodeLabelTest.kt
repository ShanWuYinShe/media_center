package com.example.mediacenter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeLabelTest {
    @Test
    fun `时间段式分P标题只显示集数`() {
        // B 站多P常见:「剧名-00.04.00.000-00.09.30.000-seg01」,截断后完全不可读
        assertEquals("第 1 集", episodeDisplayTitle(0, "12怒汉-00.04.00.000-00.09.30.000-seg01"))
        assertEquals("第 12 集", episodeDisplayTitle(11, "00:12:34.567-00:15:00.000"))
    }

    @Test
    fun `可读标题保留集数与标题`() {
        assertEquals("第 1 集 · 预告", episodeDisplayTitle(0, "预告"))
        assertEquals("第 2 集 · 花絮", episodeDisplayTitle(1, " 花絮 "))
    }

    @Test
    fun `空标题回退为集数`() {
        assertEquals("第 3 集", episodeDisplayTitle(2, null))
        assertEquals("第 3 集", episodeDisplayTitle(2, "   "))
    }
}
