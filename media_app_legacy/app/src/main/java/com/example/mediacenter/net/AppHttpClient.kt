package com.example.mediacenter.net

import android.content.Context
import com.example.mediacenter.log.AppLog
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * 统一的网络客户端工厂,确保在 Android 4.4.2 (API 19) 上能与现代 HTTPS 服务器握手成功。
 *
 * **为什么信任所有证书**:服务端证书链锚定 GTS Root R4(Google Trust Services),
 * 该根证书从 Android 7.0 (API 24) 才内置进系统信任库。Android 4.4.2 缺失该根,
 * 加上老 Conscrypt 的密码套件 / TLS 版本兼容性问题,导致证书校验层面有多个叠加
 * 的失败点(找不到信任锚、时间漂移、套件不匹配……)。
 *
 * 本 App 只消费公开视频内容、连接自己的媒体服务器,传输层认证的价值有限。
 * 因此采用 **trust-all** 策略:跳过证书链 / 有效期 / 吊销状态校验,一次性绕开
 * 老设备 TLS 的所有失败点,保证"一定能连得上"。加密本身(TLS 隧道)仍然生效——
 * 数据传输依旧加密,只是不验证对端身份。
 *
 * 主机名校验仍由 OkHttp 默认行为处理(可在 per-request 层调整);对自建媒体服务器
 * 场景,主机名匹配不是硬性需求。
 *
 * 同一套 [SSLSocketFactory] 同时用于 OkHttp 客户端,以及通过
 * [installHttpsUrlConnectionDefault] 设为 HttpsURLConnection 全局默认,
 * 覆盖 ExoPlayer DefaultHttpDataSource(走 HttpsURLConnection)的播放链路。
 */
object AppHttpClient {
    // TLS 配置延迟构建:首次 newBuilder()/installHttpsUrlConnectionDefault() 访问时初始化,
    // 彻底消除"init() 必须先于 MediaApi 构造"的时序依赖。trust-all 不需要 context,
    // 因此用无参 lazy 即可。
    private val tls: Tls? by lazy { buildTls() }

    @Volatile private var httpsDefaultInstalled = false

    /**
     * 早期初始化入口(可选)。TLS 现在是 by lazy,首次网络请求时自动构建,
     * 不再强制要求先调用此方法。保留它仅为 Application.onCreate 里预热 + 记日志。
     */
    fun init(context: Context) {
        // 触发 lazy 初始化(返回值忽略),确保 TLS 在 Application 早期就绑好。
        tls
    }

    /** 共享的基础客户端(OkHttp 默认超时),用于无特殊超时需求的场景。 */
    val shared: OkHttpClient by lazy { newBuilder().build() }

    /**
     * 带磁盘缓存的共享客户端。用于缩略图等可重复利用的图片请求——legacy 之前只有内存 LruCache,
     * 进程重启后所有缩略图重新下载,长列表冷滚动体验差。磁盘缓存 ~50MiB,限 API19 可用。
     *
     * 必须在 [initCache] 之后访问(初始化在 Application.onCreate)。
     * 未初始化时回退到无缓存的 [shared](功能不中断,只是没有磁盘缓存)。
     */
    @Volatile private var cache: Cache? = null
    val cachedShared: OkHttpClient by lazy {
        val b = newBuilder()
        cache?.let { b.cache(it) }
        b.build()
    }

    /** 初始化磁盘缓存。在 Application.onCreate 早期、任何图片请求之前调用一次。 */
    fun initCache(context: Context) {
        if (cache != null) return
        val dir = File(context.cacheDir, "okhttp-cache")
        // 50 MiB:缩略图平均 ~30KB,可缓存 ~1700 张;在低内存老盒子上也不至于吃满磁盘。
        val c = Cache(dir, 50L * 1024 * 1024)
        cache = c
        AppLog.i("Http", "disk cache enabled: ${dir.absolutePath} (50 MiB)")
    }

    /** 返回已套用 trust-all TLS 的 Builder。 */
    fun newBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        tls?.let { builder.sslSocketFactory(it.socketFactory, it.trustManager) }
        return builder
    }

    /**
     * 把 trust-all 的 [SSLSocketFactory] 设为 HttpsURLConnection 进程级默认,
     * 让 ExoPlayer DefaultHttpDataSource 等走 HttpsURLConnection 的链路也跳过证书校验。
     * 应在 Application 启动早期、任何网络请求之前调用一次;失败仅记日志,不影响启动。
     */
    @Synchronized
    fun installHttpsUrlConnectionDefault() {
        if (httpsDefaultInstalled) return
        val factory = tls?.socketFactory ?: return
        runCatching { HttpsURLConnection.setDefaultSSLSocketFactory(factory) }
            .onSuccess {
                httpsDefaultInstalled = true
                AppLog.i("Http", "installed trust-all SSLSocketFactory as HttpsURLConnection default")
            }
            .onFailure { AppLog.w("Http", "failed to install HttpsURLConnection default factory: ${it.message}", it) }
    }

    private class Tls(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    private fun buildTls(): Tls? = try {
        val trustManager = TrustAllManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        AppLog.i("Http", "TLS initialized (trust-all: certificate validation disabled for legacy device compatibility)")
        Tls(ModernTlsSocketFactory(sslContext.socketFactory), trustManager)
    } catch (error: GeneralSecurityException) {
        AppLog.w("Http", "fallback to default TLS: ${error.message}", error)
        null
    }

    /**
     * 信任所有服务器证书的 TrustManager:对所有证书链直接放行,不做任何校验。
     * 参见类注释关于安全取舍的说明。
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 不校验客户端证书。
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 信任所有服务器证书:不做链 / 有效期 / 吊销状态校验。
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * 包裹真实 [SSLSocketFactory],为每个创建出来的 [SSLSocket] 显式启用现代 TLS 版本。
     * 作用:在 TLSv1.2 默认未开启的旧系统(API 19/20)上也能与仅支持 TLS1.2+ 的服务端
     * (Cloudflare / 本站 nginx)握手;只在"受支持"集合内启用,不会因不支持的协议名抛异常,
     * 也不会降级到 SSLv3 / TLSv1.0。
     */
    private class ModernTlsSocketFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val enabled = socket.supportedProtocols.filter { it in MODERN_PROTOCOLS }
                if (enabled.isNotEmpty()) {
                    runCatching { socket.enabledProtocols = enabled.toTypedArray() }
                }
            }
            return socket
        }

        private companion object {
            val MODERN_PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")
        }
    }
}
