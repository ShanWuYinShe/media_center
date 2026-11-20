package com.example.mediacenter

import android.app.Application
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.mediacenter.crash.CrashReporter
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import com.example.mediacenter.net.BilibiliImageLoaderFactory
import com.example.mediacenter.util.StartupTrace
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
class MediaCenterApp : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Coil 进程级 ImageLoader:为 B 站图床注入 Referer/UA,避免缩略图 403 空封面。
    // 由 Coil 在首个 AsyncImage 请求前通过 SingletonImageLoader.get(context) 触发。
    override fun newImageLoader(): ImageLoader = BilibiliImageLoaderFactory.build(this)

    override fun onCreate() {
        super.onCreate()
        StartupTrace.markApplicationCreate()
        AppLog.init(this)
        // 注入全局 repository 的持久化 context(provider 选择重启后恢复)
        com.example.mediacenter.data.MediaRepositoryHolder.init(this)
        // 设备性能档位(低端盒子降级骨架动画/缓存容量),启动时判定一次。
        com.example.mediacenter.util.DevicePerformance.init(this)
        AppLog.i("Perf", "device tier: lowEnd=${com.example.mediacenter.util.DevicePerformance.isLowEnd}")
        // 更新协调器同样需要 application context(首页/设置页共用一份更新状态)
        com.example.mediacenter.update.AppUpdateHolder.init(this)
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
        // 尽早安装 Conscrypt 作为首选 JCE Provider:让 OkHttp / HttpsURLConnection /
        // Media3 DefaultHttpDataSource 统一走 BoringSSL,解决老系统自带 SSL 不支持目标
        // 站点 TLS 握手的问题。须在任何网络请求前。
        AppHttpClient.installConscryptProvider()
        // Cloudflare 优选 IP:国内运营商 DNS 会把 media 域名解析到绕路/被 QoS 的
        // anycast 节点(实测 15 次里 8 次连不上)。这里启动后台测速,只对
        // media.chaoset.com 生效;没有测速结果前用内置兜底池,再不行退回系统 DNS。
        // 候选池由服务端下发(服务端在墙外能稳定拉取公共优选源)。
        installPreferredIps()
        installCrashHandler()
        uploadPendingCrashReport()
        warmProcessSingletons()
    }

    /**
     * 预热进程级单例,避免首次组合在主线程序列化初始化:
     * - MediaRepositoryHolder.repository:SharedPreferences 首读 + OkHttp 客户端/磁盘缓存构建;
     * - Coil ImageLoader:磁盘缓存目录与 OkHttp 栈初始化(首个 AsyncImage 才触发的 lazy)。
     * 低端 TV 盒子这两处可占几十~数百 ms;放 IO 线程后,首帧通常已就绪。
     */
    private fun warmProcessSingletons() {
        appScope.launch {
            // 播放首段缓存放到最前:SimpleCache 首次构造要打开数据库/扫描目录,
            // 而且 PlaybackService.onCreate 在主线程同步取它的工厂——一旦用户
            // 起播时这里还没预热到该步,主线程会阻塞等锁并亲自构造缓存,造成
            // 首帧前 jank。仓库/图片加载的预热可以接受稍后完成。
            runCatching {
                com.example.mediacenter.player.PlaybackMediaCache.warmUp(this@MediaCenterApp)
            }.onFailure {
                AppLog.w("App", "warm playback cache failed: ${it.message}", it)
            }
            runCatching { com.example.mediacenter.data.MediaRepositoryHolder.repository }
                .onFailure { AppLog.w("App", "warm repository failed: ${it.message}", it) }
            runCatching { Coil.imageLoader(this@MediaCenterApp) }
                .onFailure { AppLog.w("App", "warm image loader failed: ${it.message}", it) }
        }
    }

    /**
     * 启动优选 IP 后台测速。
     *
     * 候选池拉取走独立 MediaApi(与业务同一个 OkHttp 栈，因此也复用同一条
     * 优选 IP 连接):服务端下发候选 → 本机测速排序 → 只对 media 域名生效。
     * 任何失败都静默退回内置池/系统 DNS，不影响启动与播放。
     */
    private fun installPreferredIps() {
        AppHttpClient.initPreferredIps(this, BuildConfig.MEDIA_SERVER_URL) {
            com.example.mediacenter.data.MediaRepositoryHolder.updateApi.preferredIps().items
        }
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
