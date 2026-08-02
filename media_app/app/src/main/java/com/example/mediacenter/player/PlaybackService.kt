package com.example.mediacenter.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.mediacenter.log.AppLog

/**
 * 播放服务:ExoPlayer + MediaSession 常驻于此,提供系统媒体栏卡片、通知控制与媒体按键。
 * 播放页通过 MediaController 异步绑定驱动播放;播放页退出时由页面显式
 * stopService 停止(本 App 无后台播放需求,退出播放页即停止)。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
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
                DefaultMediaSourceFactory(this)
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
