package com.example.mediacenter.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailResumeTest {
    @Test
    fun `多P条目id自带页码时与播放器存储键一致`() {
        assertEquals(1, resumePageIndex("BV1xx_p2", provider = "bilibili", lastWatchedPage = 0))
    }

    @Test
    fun `观看历史页码优先于条目自带页码`() {
        assertEquals(3, resumePageIndex("BV1xx_p2", provider = "bilibili", lastWatchedPage = 3))
    }

    @Test
    fun `非多P bilibili 条目使用观看历史页码`() {
        assertEquals(0, resumePageIndex("BV1xx", provider = "bilibili", lastWatchedPage = 0))
        assertEquals(2, resumePageIndex("BV1xx", provider = "bilibili", lastWatchedPage = 2))
    }

    @Test
    fun `YouTube id 含 _p 后缀时不按分P解析`() {
        assertEquals(0, resumePageIndex("abc_p1defgh", provider = "youtube", lastWatchedPage = 0))
        assertEquals(1, resumePageIndex("abc_p1defgh", provider = "youtube", lastWatchedPage = 1))
    }
}
