package com.example.mediacenter.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDownloadProgressTest {
    @Test
    fun `百分比取整并收敛到 0-100`() {
        assertEquals("0%", UpdateDownloadProgress.percentText(0f))
        assertEquals("45%", UpdateDownloadProgress.percentText(0.45f))
        assertEquals("100%", UpdateDownloadProgress.percentText(1f))
        assertEquals("100%", UpdateDownloadProgress.percentText(1.4f))
        assertEquals("0%", UpdateDownloadProgress.percentText(-0.2f))
    }

    @Test
    fun `体积按 MB 或 GB 展示`() {
        assertEquals("0 MB", UpdateDownloadProgress.formatMegabytes(0))
        assertEquals("0 MB", UpdateDownloadProgress.formatMegabytes(-1))
        assertEquals("5.8 MB", UpdateDownloadProgress.formatMegabytes(6_082_150))
        assertEquals("1.00 GB", UpdateDownloadProgress.formatMegabytes(1024L * 1024 * 1024))
    }

    @Test
    fun `进度摘要同时给出百分比与已下载体积`() {
        assertEquals(
            "50% · 2.9 MB / 5.8 MB",
            UpdateDownloadProgress.summary(0.5f, 6_082_150),
        )
        assertEquals(
            "100% · 5.8 MB / 5.8 MB",
            UpdateDownloadProgress.summary(1f, 6_082_150),
        )
    }

    @Test
    fun `未知总体积时只展示百分比`() {
        assertEquals("37%", UpdateDownloadProgress.summary(0.37f, 0))
        assertEquals("0%", UpdateDownloadProgress.summary(0f, -1))
    }
}
