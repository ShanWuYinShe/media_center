package com.example.mediacenter.player

import android.content.Context
import android.content.Intent
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.util.BackgroundWork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放服务保活协调器。
 *
 * 播放页退出时不再立刻 stopService:先暂停播放、保留服务/ExoPlayer 20s。
 * "返回详情看一眼→马上再播"是 TV 上很常见的操作,保活窗口内重进播放页可省掉
 * 一次 ExoPlayer/MediaSession 重建与 controller 连接抖动(100–300ms)。
 *
 * 窗口内没有重新进入播放页才真正停止;新播放页挂载时取消待停止任务,
 * 避免把新会话误杀。音频在退出时已 pause,保活期间不会后台出声。
 */
object PlaybackServiceKeepAlive {
    private const val IDLE_STOP_DELAY_MS = 20_000L
    private var stopJob: Job? = null

    /** 播放页挂载/连上 controller 时调用:取消待停止任务。 */
    fun onPlayerPageAttached() {
        stopJob?.cancel()
        stopJob = null
    }

    /** 播放页退出:排定延迟停止(窗口内重入则不执行)。 */
    fun scheduleStop(context: Context) {
        stopJob?.cancel()
        val appContext = context.applicationContext
        stopJob = BackgroundWork.scope.launch {
            delay(IDLE_STOP_DELAY_MS)
            AppLog.i("Player", "playback service idle stop")
            runCatching {
                appContext.stopService(Intent(appContext, PlaybackService::class.java))
            }
        }
    }

    /** 立即停止(需要彻底退出播放会话时调用)。 */
    fun stopNow(context: Context) {
        stopJob?.cancel()
        stopJob = null
        runCatching {
            context.applicationContext.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
