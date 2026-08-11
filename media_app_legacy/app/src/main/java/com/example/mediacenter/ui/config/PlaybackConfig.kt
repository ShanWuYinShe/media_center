package com.example.mediacenter.ui.config

import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.MimeTypes
import com.example.mediacenter.data.isTransientHttpStatusCode


const val PlaybackUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// B 站 CDN 反爬要求的 PC 端 headers:playurl 直链请求(BilibiliApi)与播放流请求
// (ExoPlayer DataSource)共用同一组。单一来源,避免 B 站改反爬规则时多处各改漏改。
val BiliCdnHeaders: Map<String, String> = mapOf(
    "User-Agent" to PlaybackUserAgent,
    "Referer" to "https://www.bilibili.com",
    "Origin" to "https://www.bilibili.com",
)

const val MaxPlaybackRetries = 3

const val PlaybackStabilityResetMs = 12_000L
const val MaxThumbnailBytes = 6 * 1024 * 1024
const val BackdropAlpha = 0.55f

const val MaxThumbnailRetries = 2
val ThumbnailRetryBackoffMs = longArrayOf(400L, 1200L)

const val ViewTypeVideo = 1
const val ViewTypeFooter = 2
const val FooterStableId = Long.MIN_VALUE
const val CategorySelectDebounceMs = 280L

const val DIAGNOSTIC_LEFT_PRESS_COUNT = 5

val PlaybackRequestHeaderDefaults = mapOf(
    "Accept" to "*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

fun playbackRequestHeaders(companionHeaders: Map<String, String>): Map<String, String> {
    if (companionHeaders.isEmpty()) return PlaybackRequestHeaderDefaults
    val requestHeaders = LinkedHashMap<String, String>(companionHeaders)
    PlaybackRequestHeaderDefaults.forEach { (name, value) ->
        if (!requestHeaders.containsHeader(name)) {
            requestHeaders[name] = value
        }
    }
    return requestHeaders
}

fun Map<String, String>.containsHeader(name: String): Boolean =
    keys.any { it.equals(name, ignoreCase = true) }

fun playbackErrorMessage(error: PlaybackException): String {
    val responseCode = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
    return when (responseCode) {
        401, 403 -> "播放地址已失效或被拒绝，请重试"
        404, 410 -> "播放资源不可用，请重试或换个视频"
        502, 503, 504 -> "该视频来源暂不可用，请稍后重试或换个视频"
        else -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> "当前设备不支持该视频编码，可能需要服务端提供 H.264/AAC 版本"
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "视频解码失败，可能是编码、码率或分辨率超出当前设备能力"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> "当前播放格式不受支持，请换个清晰度或让服务端转码"
            else -> if (error.findCause<HttpDataSource.HttpDataSourceException>() != null) {
                "播放连接中断，请稍后重试"
            } else {
                "视频播放失败，请稍后重试"
            }
        }
    }
}

fun isRecoverablePlaybackError(error: PlaybackException): Boolean {
    val responseCode = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
        ?: return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> false
            else -> error.findCause<HttpDataSource.HttpDataSourceException>() != null
        }
    return responseCode == 401 || responseCode == 403 || responseCode.isTransientHttpStatusCode()
}

fun playbackMimeType(mimeType: String?): String {
    return when (mimeType?.trim()?.lowercase()) {
        "application/dash+xml", "application/mpd", "application/dash" -> MimeTypes.APPLICATION_MPD
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        -> MimeTypes.APPLICATION_M3U8
        "video/webm" -> MimeTypes.VIDEO_WEBM
        "video/mp4", null -> MimeTypes.VIDEO_MP4
        else -> mimeType
    }
}

inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
