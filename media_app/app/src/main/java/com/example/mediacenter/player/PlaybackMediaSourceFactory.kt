package com.example.mediacenter.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 支持「视频轨 + 独立音轨」合流的 [MediaSource.Factory]。
 *
 * 为什么需要:YouTube 的 1080P+ 档位通常只提供纯视频 DASH 轨与纯音频轨,
 * 服务端解析结果用 `audioUrl` 下发音轨。播放页通过 MediaController 驱动
 * PlaybackService,而 MediaController 只能传 [MediaItem]、不能直接传
 * MediaSource,因此把音轨地址放进 [MediaItem.mediaMetadata] extras,由
 * PlaybackService 的 MediaSource.Factory 在服务端本地完成合流。
 *
 * 没有 `audioUrl` 的条目(合一直链/HLS)原样交给 delegate,行为不变。
 */
@OptIn(UnstableApi::class)
internal class PlaybackMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.mediaMetadata.extras
        val segments = extras?.getStringArrayList(EXTRA_SEGMENTS).orEmpty()
            .filter { it.isNotBlank() }
        val videoSource = if (segments.size > 1) {
            // B 站多段 durl:按服务端给出的顺序拼接,播放/seek 跨段连续。
            val videoMime = mediaItem.localConfiguration?.mimeType
            val parts = segments.map { url ->
                delegate.createMediaSource(
                    MediaItem.Builder()
                        .setUri(url)
                        .apply { if (!videoMime.isNullOrBlank()) setMimeType(videoMime) }
                        .build(),
                )
            }
            ConcatenatingMediaSource(*parts.toTypedArray())
        } else {
            delegate.createMediaSource(mediaItem)
        }

        val audioUrl = extras?.getString(EXTRA_AUDIO_URL).orEmpty()
        if (audioUrl.isEmpty()) {
            return videoSource
        }
        val audioMime = extras?.getString(EXTRA_AUDIO_MIME).orEmpty()
        val audioItem = MediaItem.Builder()
            .setUri(audioUrl)
            .apply { if (audioMime.isNotEmpty()) setMimeType(audioMime) }
            .build()
        // 两个源共用同一个 data source / 错误策略:headers(如 B 站 Referer)
        // 由 DefaultHttpDataSource.Factory 统一注入。
        return MergingMediaSource(
            videoSource,
            delegate.createMediaSource(audioItem),
        )
    }

    companion object {
        const val EXTRA_AUDIO_URL = "media_center_audio_url"
        const val EXTRA_AUDIO_MIME = "media_center_audio_mime"
        const val EXTRA_SEGMENTS = "media_center_video_segments"
    }
}
