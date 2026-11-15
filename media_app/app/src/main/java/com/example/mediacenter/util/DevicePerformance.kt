package com.example.mediacenter.util

import android.app.ActivityManager
import android.content.Context

/**
 * 设备性能档位:低端盒子(系统标记低内存,或总内存 < 2GB)主动降级非关键动画与
 * 缓存容量,把有限的 CPU/内存留给首帧与播放。
 *
 * 判定只在 Application 启动时做一次(ActivityManager 调用轻量,不必每次读)。
 * 档位只做"减负"不做"减功能":播放/目录/搜索路径完全一致,仅动画频率与
 * 预取/快照容量随档位调整。
 */
object DevicePerformance {
    private const val LOW_END_TOTAL_MEM_BYTES = 2L * 1024 * 1024 * 1024

    @Volatile
    private var lowEnd = false

    val isLowEnd: Boolean get() = lowEnd

    fun init(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        runCatching { manager.getMemoryInfo(info) }
        lowEnd = isLowEndDevice(manager.isLowRamDevice, info.totalMem)
    }

    /** 纯逻辑判定(可 JVM 单测):系统低内存标记 / 总内存 < 2GB。 */
    internal fun isLowEndDevice(isLowRamDevice: Boolean, totalMemBytes: Long): Boolean {
        if (isLowRamDevice) return true
        // totalMem 读取失败时是 0(不是"内存极小"),不能据此判低端。
        return totalMemBytes in 1 until LOW_END_TOTAL_MEM_BYTES
    }
}
