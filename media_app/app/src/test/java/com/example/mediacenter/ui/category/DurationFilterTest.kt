package com.example.mediacenter.ui.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 时长档位边界必须与 service 端 min/maxDurationSeconds(含边界)严格一致。 */
class DurationFilterTest {
    @Test
    fun `全部档位不带任何过滤`() {
        assertNull(DurationFilter.ALL.minSeconds)
        assertNull(DurationFilter.ALL.maxSeconds)
    }

    @Test
    fun `短片严格小于 10 分钟`() {
        assertNull(DurationFilter.SHORT.minSeconds)
        assertEquals(599L, DurationFilter.SHORT.maxSeconds)
    }

    @Test
    fun `中片覆盖 10 到 45 分钟`() {
        assertEquals(600L, DurationFilter.MEDIUM.minSeconds)
        assertEquals(2699L, DurationFilter.MEDIUM.maxSeconds)
    }

    @Test
    fun `长片从 45 分钟起`() {
        assertEquals(2700L, DurationFilter.LONG.minSeconds)
        assertNull(DurationFilter.LONG.maxSeconds)
    }

    @Test
    fun `档位区间首尾相接且不重叠`() {
        assertEquals(DurationFilter.SHORT.maxSeconds!! + 1, DurationFilter.MEDIUM.minSeconds)
        assertEquals(DurationFilter.MEDIUM.maxSeconds!! + 1, DurationFilter.LONG.minSeconds)
    }
}
