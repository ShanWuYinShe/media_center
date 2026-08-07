package com.example.mediacenter.ui.player

import android.content.Context
import androidx.core.content.edit
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.Gravity
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.example.mediacenter.MainActivity
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.config.PlaybackStabilityResetMs

internal fun MainActivity.releasePlayer() {
        cancelPlaybackStabilityReset()
        // 释放播放器前保存播放位置，所有非零进度都保存。
        val pos = player?.currentPosition ?: 0L
        player?.release()
        if (pos > 0L) savePlaybackPosition(pos)
        player = null
        currentPlayerUrl = null
        currentPlayerOptionId = null
        playerSurface = null
        orientationIndicator = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // 播放进度持久化，方便下次续播。
internal fun MainActivity.prefs() = getSharedPreferences("playback", Context.MODE_PRIVATE)

internal fun MainActivity.playbackPosKey() = currentPlayerOptionId?.let { "pos_$it" } ?: ""

internal fun MainActivity.savePlaybackPosition(pos: Long) {
        val key = playbackPosKey()
        if (key.isEmpty()) return
        prefs().edit { putLong(key, pos) }
        AppLog.i("Player", "saved position $pos for $key")
    }

internal fun MainActivity.loadPlaybackPosition(): Long {
        val key = playbackPosKey()
        if (key.isEmpty()) return 0L
        return prefs().getLong(key, 0L)
    }

internal fun MainActivity.applyPlayerVideoRotation() {
        val surface = playerSurface
        val parent = surface?.parent as? FrameLayout
        if (surface != null && parent != null) {
            val parentWidth = parent.width
            val parentHeight = parent.height
            if (parentWidth > 0 && parentHeight > 0) {
                val swapSize = playerVideoRotationDegrees % 180 != 0
                val targetWidth = if (swapSize) parentHeight else parentWidth
                val targetHeight = if (swapSize) parentWidth else parentHeight
                val params = (surface.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
                if (params.width != targetWidth ||
                    params.height != targetHeight ||
                    params.gravity != Gravity.CENTER
                ) {
                    params.width = targetWidth
                    params.height = targetHeight
                    params.gravity = Gravity.CENTER
                    surface.layoutParams = params
                }
                surface.rotation = playerVideoRotationDegrees.toFloat()
            }
        }
        updateOrientationIndicator()
    }

    // 用户用长按 OK / MENU 在旋转角度间切换;标记为手动选择,后续不再被自动检测覆盖。
internal fun MainActivity.togglePlayerOrientation() {
        playerVideoRotationDegrees = nextManualVideoRotation(playerVideoRotationDegrees)
        playerVideoRotationUserSet = true
        AppLog.i("PlayerKey", "togglePlayerOrientation -> rotation=$playerVideoRotationDegrees")
        applyPlayerVideoRotation()
    }

    // 根据视频真实分辨率自动判定:竖向编码的视频在 TV 上默认旋转 90 度横看;
    // 若流本身带有未应用的旋转元数据,优先使用元数据角度。用户手动选择过则不覆盖。
internal fun MainActivity.autoDetectPlayerVideoRotation(videoSize: VideoSize) {
        if (playerVideoRotationUserSet) return
        @Suppress("DEPRECATION")
        val metadataRotation = normalizeVideoRotation(videoSize.unappliedRotationDegrees)
        val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val detectedRotation = if (metadataRotation != 0) {
            metadataRotation
        } else if (videoSize.height > displayWidth) {
            90
        } else {
            0
        }
        if (detectedRotation != playerVideoRotationDegrees) {
            playerVideoRotationDegrees = detectedRotation
            applyPlayerVideoRotation()
        } else {
            updateOrientationIndicator()
        }
    }

internal fun MainActivity.updateOrientationIndicator() {
        orientationIndicator?.setTextIfChanged("画面 · ${videoRotationLabel()}")
    }

internal fun MainActivity.nextManualVideoRotation(currentDegrees: Int): Int {
        return when (normalizeVideoRotation(currentDegrees)) {
            0 -> 90
            90 -> 270
            270 -> 180
            else -> 0
        }
    }

internal fun MainActivity.normalizeVideoRotation(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            90, 180, 270 -> normalized
            else -> 0
        }
    }

internal fun MainActivity.videoRotationLabel(): String {
        return when (normalizeVideoRotation(playerVideoRotationDegrees)) {
            90 -> "右转 90°"
            180 -> "旋转 180°"
            270 -> "左转 90°"
            else -> "原始"
        }
    }

    // 离开播放页时清空播放页专用的旋转 UI 引用;浏览/详情始终维持 TV 默认横屏布局。
internal fun MainActivity.resetPlayerOrientationToDefault() {
        orientationIndicator = null
    }

    // STATE_READY 后启动稳定计时:延迟 [PlaybackStabilityResetMs] 后,若仍在顺畅播放
    // 同一条流,则通知 ViewModel 这条流确实可用、重置恢复额度。任何失败/结束/缓冲
    // 或重建播放器都会取消该计时,从而不会被“就绪即失败”的流误判为稳定。
internal fun MainActivity.schedulePlaybackStabilityReset(streamId: String) {
        cancelPlaybackStabilityReset()
        val runnable = Runnable {
            playbackStabilityRunnable = null
            val activePlayer = player
            if (activePlayer != null &&
                activePlayer.playbackState == Player.STATE_READY &&
                activePlayer.playWhenReady
            ) {
                AppLog.i("Player", "stream $streamId stable for ${PlaybackStabilityResetMs}ms")
                viewModel.playbackSustained(streamId)
            }
        }
        playbackStabilityRunnable = runnable
        root.postDelayed(runnable, PlaybackStabilityResetMs)
    }

internal fun MainActivity.cancelPlaybackStabilityReset() {
        playbackStabilityRunnable?.let { root.removeCallbacks(it) }
        playbackStabilityRunnable = null
    }
