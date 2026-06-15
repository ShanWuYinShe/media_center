package com.example.mediacenter

import androidx.multidex.MultiDexApplication
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient

/**
 * 自定义 Application:尽早初始化文件日志并安装全局未捕获异常处理器,
 * 把崩溃栈同步落盘后再交还系统默认处理器(保留正常的崩溃/重启行为)。
 * 这样「播放异常导致进程被杀、重启回首页」这类问题能在重启后从日志文件里查到根因。
 */
class MediaCenterApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "process start")
        // 尽早安装 OCSP soft-fail 的 TLS 默认工厂:覆盖 Media3 DefaultHttpDataSource(走
        // HttpsURLConnection)的播放链路,避免时钟不准的设备在播放时握手失败。须在任何网络请求前。
        AppHttpClient.installHttpsUrlConnectionDefault()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.logFatalBlocking("Crash", "Uncaught exception on thread ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
