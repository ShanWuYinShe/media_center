package com.example.mediacenter.player

import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaCacheTest {
    private fun option(
        url: String = "https://cdn.example.com/video.m4s?token=abc",
        segments: List<String>? = null,
        audioUrl: String? = null,
        mimeType: String? = null,
    ) = PlaybackOption(
        id = "1",
        kind = "video",
        label = "360P",
        url = url,
        segments = segments,
        audioUrl = audioUrl,
        mimeType = mimeType,
    )

    @Test
    fun prefetchTargets_multiSegment_warmsFirstSegmentAndAudio() {
        val result = PlaybackResult(
            options = listOf(
                option(
                    url = "https://cdn.example.com/main.m4s",
                    segments = listOf(
                        "https://cdn.example.com/part1.m4s",
                        "https://cdn.example.com/part2.m4s",
                    ),
                    audioUrl = "https://audio.example.com/track.m4s",
                ),
            ),
        )

        assertEquals(
            listOf(
                "https://cdn.example.com/part1.m4s",
                "https://audio.example.com/track.m4s",
            ),
            PlaybackMediaCache.prefetchTargets(result),
        )
    }

    @Test
    fun prefetchTargets_singleSegment_usesDirectUrl() {
        val option = option(
            url = "https://cdn.example.com/main.m4s",
            segments = listOf("https://cdn.example.com/only.m4s"),
        )

        assertEquals(
            listOf("https://cdn.example.com/main.m4s"),
            PlaybackMediaCache.prefetchTargets(option),
        )
    }

    @Test
    fun prefetchTargets_videoOnly_hasSingleTarget() {
        assertEquals(
            listOf("https://cdn.example.com/video.m4s?token=abc"),
            PlaybackMediaCache.prefetchTargets(option()),
        )
    }

    @Test
    fun prefetchTargets_skipsHlsPlaylist() {
        assertEquals(
            emptyList<String>(),
            PlaybackMediaCache.prefetchTargets(option(url = "https://cdn.example.com/master.m3u8")),
        )
        assertEquals(
            emptyList<String>(),
            PlaybackMediaCache.prefetchTargets(
                option(
                    url = "https://cdn.example.com/video.m4s",
                    mimeType = "application/x-mpegURL",
                ),
            ),
        )
    }

    @Test
    fun prefetchTargets_skipsDashManifest() {
        assertEquals(
            emptyList<String>(),
            PlaybackMediaCache.prefetchTargets(option(url = "https://cdn.example.com/manifest.mpd")),
        )
    }

    @Test
    fun shouldPrefetchTail_onlyForMergedProgressiveStream() {
        assertTrue(PlaybackMediaCache.shouldPrefetchTail(option()))
        assertFalse(
            PlaybackMediaCache.shouldPrefetchTail(
                option(audioUrl = "https://audio.example.com/track.m4s"),
            ),
        )
    }

    @Test
    fun prefetchTargets_emptyOptions_returnsEmpty() {
        assertEquals(emptyList<String>(), PlaybackMediaCache.prefetchTargets(PlaybackResult(emptyList())))
    }
}