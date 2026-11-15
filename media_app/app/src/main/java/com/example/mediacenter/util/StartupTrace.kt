package com.example.mediacenter.util

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.example.mediacenter.log.AppLog

/**
 * 冷启动时间线:记录「进程创建 → Application.onCreate → 首帧」各阶段耗时,
 * 输出到文件日志(阶段 0 的可验收基线)。
 *
 * 为什么用 uptimeMillis:TV 盒子常无 perfetto/adb 环境,现场只能靠日志;
 * Process.getStartUptimeMillis()(API 24+)给出进程真实创建时刻,低版本退回
 * 模块首次触达时刻,至少能测 Application→首帧。
 */
object StartupTrace {
    private const val TAG = "Perf"

    private val processStartUptimeMs: Long by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Process.getStartUptimeMillis()
        } else {
            SystemClock.uptimeMillis()
        }
    }

    private var applicationCreateUptimeMs = 0L
    private var firstFrameLogged = false

    /** Application.onCreate 入口调用(越早越准)。 */
    fun markApplicationCreate() {
        applicationCreateUptimeMs = SystemClock.uptimeMillis()
    }

    /** 首帧绘制前调用一次:输出 TTFF(进程→首帧)与 App 初始化耗时。 */
    fun markFirstFrame(activity: String) {
        if (firstFrameLogged) return
        firstFrameLogged = true
        val now = SystemClock.uptimeMillis()
        val processToFrame = now - processStartUptimeMs
        val appToFrame = if (applicationCreateUptimeMs > 0) now - applicationCreateUptimeMs else -1
        AppLog.i(
            TAG,
            "cold start [$activity]: process→firstFrame=${processToFrame}ms, " +
                "appOnCreate→firstFrame=${appToFrame}ms",
        )
    }
}
