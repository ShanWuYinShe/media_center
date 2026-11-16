package com.example.mediacenter.player

import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackWarmupTest {
    private fun option(
        url: String = "https://cdn.example.com/video.m4s?token=abc",
        segments: List<String>? = null,
        audioUrl: String? = null,
    ) = PlaybackOption(
        id = "1",
        kind = "video",
        label = "360P",
        url = url,
        segments = segments,
        audioUrl = audioUrl,
    )

    @Test
    fun warmUrls_includesDirectSegmentAndAudio() {
        val result = PlaybackResult(
            options = listOf(
                option(
                    url = "https://cdn.example.com/main.m4s?token=abc",
                    segments = listOf(
                        "https://cdn.example.com/part1.m4s?token=abc",
                        "https://cdn.example.com/part2.m4s?token=abc",
                    ),
                    audioUrl = "https://audio.example.com/track.m4s?token=abc",
                ),
            ),
        )

        assertEquals(
            listOf(
                "https://cdn.example.com/main.m4s?token=abc",
                "https://cdn.example.com/part1.m4s?token=abc",
                "https://audio.example.com/track.m4s?token=abc",
            ),
            PlaybackWarmup.warmUrls(result),
        )
    }

    @Test
    fun warmUrls_singleSegmentOnlyWarmsDirectUrl() {
        val result = PlaybackResult(
            options = listOf(option(segments = listOf("https://cdn.example.com/only.m4s?token=abc"))),
        )

        assertEquals(
            listOf("https://cdn.example.com/video.m4s?token=abc"),
            PlaybackWarmup.warmUrls(result),
        )
    }

    @Test
    fun warmUrls_emptyOptions_returnsEmpty() {
        assertEquals(emptyList<String>(), PlaybackWarmup.warmUrls(PlaybackResult(emptyList())))
    }

    @Test
    fun warmKey_ignoresSignedQuery() {
        assertEquals(
            "https://cdn.example.com/video.m4s",
            PlaybackWarmup.warmKey("https://cdn.example.com/video.m4s?token=abc&expires=1"),
        )
    }
}
