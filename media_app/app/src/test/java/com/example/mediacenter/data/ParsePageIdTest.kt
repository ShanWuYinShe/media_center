package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ParsePageIdTest {
    @Test
    fun `无 _p 后缀返回原 id 与第 0 页`() {
        assertEquals("BV1xx411c7mD" to 0, parsePageId("BV1xx411c7mD"))
    }

    @Test
    fun `_pN 返回原 id 与 0 起页码`() {
        assertEquals("BV1xx411c7mD" to 2, parsePageId("BV1xx411c7mD_p3"))
        assertEquals("BV1xx411c7mD" to 0, parsePageId("BV1xx411c7mD_p1"))
    }

    @Test
    fun `非法页码后缀按单视频处理`() {
        // 页码非数字
        assertEquals("BV1xx411c7mD_pX" to 0, parsePageId("BV1xx411c7mD_pX"))
        // 页码为 0(非法,页码从 1 起)
        assertEquals("BV1xx411c7mD_p0" to 0, parsePageId("BV1xx411c7mD_p0"))
    }

    @Test
    fun `取最后一个 _p 后缀`() {
        assertEquals("BV1xx_p2" to 4, parsePageId("BV1xx_p2_p5"))
    }

    @Test
    fun `_p 位于起始位置时不视为页码分隔`() {
        assertEquals("_p2" to 0, parsePageId("_p2"))
    }
}
