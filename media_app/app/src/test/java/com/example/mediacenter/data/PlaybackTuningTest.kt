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
    fun `非低端机保持原始档位`() {
        val original = result(2160, 1080, 720, 480)
        assertSame(original, PlaybackTuning.preferSmoothInitialOption(original, lowEnd = false))
    }

    @Test
    fun `低端机默认收敛到 720P 且保留其余档位`() {
        val tuned = PlaybackTuning.preferSmoothInitialOption(result(2160, 1080, 720, 480), lowEnd = true)

        assertEquals(720, tuned.options.first().qn)
        assertEquals(4, tuned.options.size)
        assertEquals(setOf(2160, 1080, 720, 480), tuned.options.mapNotNull { it.qn }.toSet())
    }

    @Test
    fun `已经是低清晰度时不做调整`() {
        val original = result(720, 480)
        assertSame(original, PlaybackTuning.preferSmoothInitialOption(original, lowEnd = true))
    }

    @Test
    fun `没有更低档位时保持原样`() {
        val original = result(2160, 1080)
        assertSame(original, PlaybackTuning.preferSmoothInitialOption(original, lowEnd = true))
    }

    @Test
    fun `单档位与未知 qn 不做调整`() {
        val single = result(2160)
        assertSame(single, PlaybackTuning.preferSmoothInitialOption(single, lowEnd = true))
        val unknown = PlaybackResult(options = listOf(option(0), option(480)))
        assertSame(unknown, PlaybackTuning.preferSmoothInitialOption(unknown, lowEnd = true))
    }
}
