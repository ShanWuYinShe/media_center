package com.example.mediacenter

import android.os.Build
import androidx.multidex.MultiDexApplication
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import com.example.mediacenter.player.LegacyPlaybackMediaCache

/**
 * 自定义 Application:尽早初始化文件日志并安装全局未捕获异常处理器,
 * 把崩溃栈同步落盘后再交还系统默认处理器(保留正常的崩溃/重启行为)。
 * 这样「播放异常导致进程被杀、重启回首页」这类问题能在重启后从日志文件里查到根因。
 */
class MediaCenterApp : MultiDexApplication() {

    companion object {
        /** 进程级 Application 引用:非 UI 组件(如 ViewModel)需要 context 时使用。 */
        @Volatile
        var instance: MediaCenterApp? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
        // TLS 初始化:安装 Conscrypt(BoringSSL,官方支持 API 19+)+ 把内置的
        // GTS Root R4(4.4.2 系统信任库缺失,但媒体服务器链锚定它)并进信任库,
        // 构建"校验优先 + soft-fail"的 TrustManager(见 AppHttpClient 类注释)。
        // 必须在任何网络请求之前完成,否则 OkHttp / HttpsURLConnection 用的仍是
        // 平台默认 TLS——Android 4.4.2 的老 TLS 栈(OpenSSL 1.0.1)与 Cloudflare
        // 等现代服务器握手失败(缺 TLS 1.2 默认启用 / 现代套件 / GTS 根)。
        // TLS 初始化与媒体缓存构造(Conscrypt/AndroidCAStore/TrustManager、
        // SimpleCache 打开数据库/扫描目录)在老盒子上可达数十~数百 ms:放后台线程
        // 预热,不占冷启动首帧;首个网络客户端/首次播放会同步等待就绪
        // (AppHttpClient.newBuilder/awaitInit、LegacyPlaybackMediaCache.cache 的锁)。
        AppHttpClient.initAsync(this)
        // Cloudflare 优选 IP:国内运营商 DNS 会把 media 域名解析到绕路/被 QoS 的
        // anycast 节点(线上实测 15 次里 8 次连不上)。后台拉服务端候选池 + 本机
        // 测速排序，只对 media.chaoset.com 生效;失败退回内置池/系统 DNS。
        installPreferredIps()
        LegacyPlaybackMediaCache.prewarmAsync(this)
        installCrashHandler()
    }

    /**
     * 启动优选 IP 后台测速(幂等)。
     *
     * 候选池拉取用独立 MediaApi(与业务同一 OkHttp 栈，共用同一条优选 IP 连接)。
     * 任何失败都静默退回内置池/系统 DNS，不影响启动与播放。
     */
    private fun installPreferredIps() {
        val api = com.example.mediacenter.data.MediaApi(
            com.example.mediacenter.BuildConfig.MEDIA_SERVER_URL,
        )
        com.example.mediacenter.net.PreferredIpStore.initAsync(this) { api.preferredIps() }
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
