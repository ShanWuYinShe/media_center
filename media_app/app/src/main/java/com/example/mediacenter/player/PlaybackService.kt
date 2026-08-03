package com.example.mediacenter.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.mediacenter.log.AppLog

/**
 * B 站 CDN 反爬必需的请求头:PC 端 User-Agent + Referer/Origin 指向 bilibili.com。
 * 不带这些头,CDN 直接返回 403,播放流无法加载。与 [com.example.mediacenter.data.BilibiliApi]
 * 解析 playurl 时使用的头保持一致;此处集中定义,供播放服务的 HttpDataSource 复用。
 */
internal val BILI_CDN_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer" to "https://www.bilibili.com",
    "Origin" to "https://www.bilibili.com",
)

/**
 * 播放服务:ExoPlayer + MediaSession 常驻于此,提供系统媒体栏卡片、通知控制与媒体按键。
 * 播放页通过 MediaController 异步绑定驱动播放;播放页退出时由页面显式
 * stopService 停止(本 App 无后台播放需求,退出播放页即停止)。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // ExoPlayer 的 progressive 流不会自动读取 MediaItem.requestHeaders(该字段已 @Deprecated
        // 且 progressive 管线不消费它),因此必须在 HttpDataSource.Factory 上注入 B 站 CDN 必需头,
        // 否则 setMediaItem(MediaItem.fromUri(durl)) 发出的 CDN 请求会因缺 Referer/UA 被 403。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BILI_CDN_HEADERS.getValue("User-Agent"))
            .setDefaultRequestProperties(BILI_CDN_HEADERS - "User-Agent")
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // 音频焦点由播放页统一请求/释放;这里关闭 ExoPlayer 的自动焦点处理,
                // 否则服务启动时二次请求焦点会抢占页面焦点、打断自动播放。
                false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(httpFactory)
                    .setLoadErrorHandlingPolicy(CappedLoadErrorHandlingPolicy(maxRetries = 2)),
            )
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
        AppLog.i("Player", "PlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // 系统清理任务卡片:正在播放时不自杀,暂停/结束状态则停止
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.playbackState == Player.STATE_ENDED ||
            player.playbackState == Player.STATE_IDLE
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        AppLog.i("Player", "PlaybackService destroyed")
        super.onDestroy()
    }
}
