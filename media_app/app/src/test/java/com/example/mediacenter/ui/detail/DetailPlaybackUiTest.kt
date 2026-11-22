package com.example.mediacenter.ui.detail

import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.QualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPlaybackUiTest {
    private fun option(qn: Int, label: String = "${qn}P"): PlaybackOption =
        PlaybackOption(id = "q$qn", kind = "vod", label = label, url = "https://x/$qn", qn = qn)

    @Test
    fun `清晰度按自动选档上限高亮,不误报最高档`() {
        val result = PlaybackResult(
            options = listOf(option(2160), option(1080), option(720), option(480)),
            availableQualities = listOf(
                QualityOption(2160, "2160P"),
                QualityOption(1080, "1080P"),
                QualityOption(720, "720P"),
                QualityOption(480, "480P"),
            ),
        )

        val ui = detailPlaybackUi(result, maxInitialQualityQn = 720, resumePage = 0)

        // 与播放页同口径:自动选档 720P,详情页高亮/按钮文案不能显示 2160P。
        assertEquals(720, ui.currentQualityQn)
        assertEquals("720P", ui.currentQualityLabel)
        assertEquals(listOf(2160, 1080, 720, 480), ui.qualityOptions.map { it.qn })
        assertEquals(0, ui.totalPages)
        assertEquals(0, ui.pageIndex)
    }

    @Test
    fun `手动选档不受上限限制时显示选中档`() {
        val result = PlaybackResult(
            options = listOf(option(2160), option(1080)),
            availableQualities = listOf(QualityOption(2160, "2160P"), QualityOption(1080, "1080P")),
        )

        val ui = detailPlaybackUi(result, maxInitialQualityQn = Int.MAX_VALUE, resumePage = 0)

        assertEquals(2160, ui.currentQualityQn)
        assertEquals("2160P", ui.currentQualityLabel)
    }

    @Test
    fun `单流但有多档可选时仍显示清晰度行`() {
        // B 站:每次解析只回当前档一条流,可切换档位在 availableQualities 里。
        val result = PlaybackResult(
            options = listOf(option(80, "高清 1080P")),
            availableQualities = listOf(QualityOption(80, "高清 1080P"), QualityOption(64, "高清 720P")),
        )

        val ui = detailPlaybackUi(result, maxInitialQualityQn = Int.MAX_VALUE, resumePage = 0)

        assertEquals(80, ui.currentQualityQn)
        assertEquals("高清 1080P", ui.currentQualityLabel)
        assertEquals(listOf(80, 64), ui.qualityOptions.map { it.qn })
    }

    @Test
    fun `只有单档时不显示清晰度切换行但保留当前档文案`() {
        val result = PlaybackResult(
            options = listOf(option(32, "流畅 360P")),
            availableQualities = listOf(QualityOption(32, "流畅 360P")),
        )

        val ui = detailPlaybackUi(result, maxInitialQualityQn = Int.MAX_VALUE, resumePage = 0)

        assertEquals(32, ui.currentQualityQn)
        assertEquals("流畅 360P", ui.currentQualityLabel)
        assertTrue(ui.qualityOptions.isEmpty())
    }

    @Test
    fun `多P按续播页与解析页较大值取当前集并夹到合法范围`() {
        val pages = PlaybackPages(
            currentPage = 2,
            totalPages = 5,
            pageTitles = List(5) { "P${it + 1}" },
        )
        val result = PlaybackResult(options = listOf(option(64, "720P")), pages = pages)

        assertEquals(2, detailPlaybackUi(result, Int.MAX_VALUE, resumePage = 0).pageIndex)
        assertEquals(4, detailPlaybackUi(result, Int.MAX_VALUE, resumePage = 4).pageIndex)
        // 历史记录里的页码可能因视频被删页而过期:必须夹回合法区间。
        assertEquals(4, detailPlaybackUi(result, Int.MAX_VALUE, resumePage = 99).pageIndex)
        assertEquals(5, detailPlaybackUi(result, Int.MAX_VALUE, resumePage = 0).totalPages)
    }

    @Test
    fun `单P不显示选集且页码归零`() {
        val result = PlaybackResult(
            options = listOf(option(64, "720P")),
            pages = PlaybackPages(currentPage = 0, totalPages = 1),
        )

        val ui = detailPlaybackUi(result, Int.MAX_VALUE, resumePage = 3)

        assertEquals(1, ui.totalPages)
        assertEquals(0, ui.pageIndex)
    }
}
