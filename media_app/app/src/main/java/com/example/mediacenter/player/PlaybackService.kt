package com.example.mediacenter.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.BILI_CDN_HEADERS

/**
 * 播放服务:ExoPlayer + MediaSession 常驻于此,提供系统媒体栏卡片、通知控制与媒体按键。
 * 播放页通过 MediaController 异步绑定驱动播放;播放页退出时先暂停,并由
 * [PlaybackServiceKeepAlive] 排定延迟停止(20s 保活窗口,快速回进播放页可复用
 * 同一会话;本 App 无后台播放需求,窗口结束即停止)。
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
            // B 站 CDN 个别直链会 http↔https 302,默认不跟随跨协议重定向会直接加载失败
            .setAllowCrossProtocolRedirects(true)
        // 播放数据源再套一层 Media3 SimpleCache:解析后预取的首段媒体命中后
        // 直接读磁盘,不再等 CDN 握手/首字节(预取与缓存实现见 PlaybackMediaCache)。
        // 缓存不可用/未命中/损坏时自动回退 httpFactory,播放行为不变。
        val cacheFactory = PlaybackMediaCache.cacheDataSourceFactory(this, httpFactory)
        // 视频轨 + 独立音轨(YouTube 1080P+ 的 DASH 形态)在服务端本地合流;
        // MediaController 只能传 MediaItem,合流决策因此放在 MediaSource 层。
        val defaultFactory = DefaultMediaSourceFactory(cacheFactory)
            .setLoadErrorHandlingPolicy(CappedLoadErrorHandlingPolicy(maxRetries = 2))
        val player = ExoPlayer.Builder(this)
            // 起播缓冲调优:默认 bufferForPlayback=2.5s,解析完成后还要再等缓冲才出画;
            // 降到 1.2s 让起播更快,重缓冲阈值降到 3s(配合"连续失败自动降档"兜底,
            // 弱网下优先保证起播,而不是先攒 5s 再播)。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 50_000,
                        /* bufferForPlaybackMs = */ 1_200,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                    )
                    .build(),
            )
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
            .setMediaSourceFactory(PlaybackMediaSourceFactory(defaultFactory))
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
