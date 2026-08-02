package com.example.mediacenter

import android.app.Application
import android.os.Build
import com.example.mediacenter.crash.CrashReporter
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自定义 Application:尽早初始化文件日志并安装全局未捕获异常处理器,
 * 把崩溃栈同步落盘后再交还系统默认处理器(保留正常的崩溃/重启行为)。
 * 同时把待上报的崩溃文件 POST 到 server(/api/crash/report)。
 * 这样「播放异常导致进程被杀、重启回首页」这类问题能在重启后从日志文件里查到根因,
 * 并汇总到服务端便于远程诊断。
 */
class MediaCenterApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // 记录真实系统版本:国产 TV 盒子常在设置里显示 4.4.2 但 Build.VERSION 实际是 21+
        // (魔改以骗过 minSdk 检查)。这条日志用于戳穿"显示版本≠真实内核版本"。
        AppLog.i(
            "App",
            "process start: SDK_INT=${Build.VERSION.SDK_INT} " +
                "RELEASE=${Build.VERSION.RELEASE} " +
                "MANUFACTURER=${Build.MANUFACTURER} " +
                "MODEL=${Build.MODEL} " +
                "DEVICE=${Build.DEVICE}",
        )
        // 尽早安装 OCSP soft-fail 的 TLS 默认工厂:覆盖 Media3 DefaultHttpDataSource(走
        // HttpsURLConnection)的播放链路,避免时钟不准的设备在播放时握手失败。须在任何网络请求前。
        AppHttpClient.installHttpsUrlConnectionDefault()
        installCrashHandler()
        uploadPendingCrashReport()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.logFatalBlocking("Crash", "Uncaught exception on thread ${thread.name}", throwable)
                CrashReporter.write(this, throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 启动时异步上报上次崩溃(失败保留,下次启动再试),不阻塞启动流程。 */
    private fun uploadPendingCrashReport() {
        appScope.launch { CrashReporter.uploadPending(applicationContext) }
    }
}
