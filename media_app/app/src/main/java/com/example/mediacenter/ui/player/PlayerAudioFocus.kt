package com.example.mediacenter.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import com.example.mediacenter.log.AppLog

/**
 * 播放页音频焦点:抢占时暂停、暂时丢失后恢复、可 duck 时降音量。
 *
 * controller 就绪后才请求焦点:解析/连接失败时不会平白压低其它 App 的声音。
 * 每次 controller 变化(重连)重新请求,离开页面归还。
 */
@Composable
internal fun PlayerAudioFocus(controller: MediaController?) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val currentController by rememberUpdatedState(controller)
    var resumeAfterTransient by remember { mutableStateOf(false) }

    val listener = remember {
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久丢失后不应在未来的 AUDIOFOCUS_GAIN 里自动恢复播放
                    resumeAfterTransient = false
                    currentController?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterTransient = currentController?.isPlaying == true
                    currentController?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> currentController?.setVolume(0.3f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    currentController?.setVolume(1f)
                    if (resumeAfterTransient) {
                        resumeAfterTransient = false
                        currentController?.play()
                    }
                }
            }
        }
    }

    DisposableEffect(controller) {
        if (controller != null) {
            // minSdk 23 兼容:只能用旧版 AudioFocus API(API 26+ 的 AudioFocusRequest 不可用)。
            @Suppress("DEPRECATION")
            val granted = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AppLog.w("Player", "audio focus not granted (result=$granted); may compete with other apps")
            }
        }
        onDispose {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }
}
