package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackTuningTest {
    private fun option(qn: Int) = PlaybackOption(
        id = "q$qn",
        kind = "vod",
        label = "${qn}P",
        url = "https://example.test/$qn",
        qn = qn,
    )

    private fun result(vararg qns: Int) = PlaybackResult(
        options = qns.map(::option),
        availableQualities = qns.map { QualityOption(it, "${it}P") },
    )

    @Test
    fun `上限为未知时不调整`() {
        val original = result(2160, 1080, 720, 480)
        assertSame(original, PlaybackTuning.preferInitialOption(original, maxQn = 0))
        assertSame(original, PlaybackTuning.preferInitialOption(original, maxQn = Int.MAX_VALUE))
    }

    @Test
    fun `按屏幕高度收敛到 1080P 且保留其余档位`() {
        val tuned = PlaybackTuning.preferInitialOption(result(2160, 1080, 720), maxQn = 1080)

        assertEquals(1080, tuned.options.first().qn)
        assertEquals(3, tuned.options.size)
        assertEquals(setOf(2160, 1080, 720), tuned.options.mapNotNull { it.qn }.toSet())
    }

    @Test
    fun `低端机上限 720P`() {
        val tuned = PlaybackTuning.preferInitialOption(result(2160, 1080, 720, 480), maxQn = 720)

        assertEquals(720, tuned.options.first().qn)
        assertEquals(4, tuned.options.size)
    }

    @Test
    fun `已经是上限以内时不做调整`() {
        val original = result(720, 480)
        assertSame(original, PlaybackTuning.preferInitialOption(original, maxQn = 1080))
        assertSame(original, PlaybackTuning.preferInitialOption(original, maxQn = 720))
    }

    @Test
    fun `没有上限以内的档位时保持原样`() {
        val original = result(2160, 1440)
        assertSame(original, PlaybackTuning.preferInitialOption(original, maxQn = 720))
    }

    @Test
    fun `单档位与未知 qn 不做调整`() {
        val single = result(2160)
        assertSame(single, PlaybackTuning.preferInitialOption(single, maxQn = 720))
        val unknown = PlaybackResult(options = listOf(option(0), option(480)))
        assertSame(unknown, PlaybackTuning.preferInitialOption(unknown, maxQn = 720))
    }

    @Test
    fun `自动选档上限在非低端设备取屏幕高度`() {
        // JVM 单测不会调用 DevicePerformance.init,默认非低端。
        assertEquals(1080, PlaybackTuning.maxInitialQualityQn(1080))
    }
}
