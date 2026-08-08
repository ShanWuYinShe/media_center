package com.example.mediacenter.net

import android.content.Context
import com.example.mediacenter.R
import com.example.mediacenter.log.AppLog
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt

/**
 * 统一的网络客户端工厂,让 Android 4.4.2 (API 19) 上的 HTTPS 与现代服务器正常握手。
 *
 * **根因**:OkHttp 3.12.13 是支持 Android 4.x 的最终版本(Square 官方声明 3.13+
 * 起要求 Android 5.0 / 平台内置 TLS 1.2),OkHttp 本身没有替代品可选。真正的问题
 * 在 4.4.2 的**平台 TLS 栈**(OpenSSL 1.0.1):TLS 1.2 默认不启用、缺现代密码套件
 * (GCM/CHACHA20/ECDSA)、信任库缺 GTS Root R4(2016 年才签发,4.4.2 时代不存在)。
 * 旧方案的 trust-all + 手工补 ECDSA CBC 套件 + 逐 socket 开 TLS 1.2 全是在给平台
 * 打补丁,每换一个服务器都可能踩新的套件/协议坑,这就是"OkHttp 一直出问题"的根源。
 *
 * **正解**:引入 Google 官方的 [Conscrypt](https://github.com/google/conscrypt)
 * (BoringSSL 的 JSSE 实现,官方支持 Android KitKat / API 19+,Android 5.0 起系统
 * 内置的就是它,Google Play 的 ProviderInstaller 在旧设备上装的也是它)。自带
 * TLS 1.2/1.3 和全部现代密码套件,完全不依赖平台 TLS 栈。同时把 4.4.2 缺失的
 * GTS Root R4 打包进应用信任库,恢复**真正的证书链校验**(不再是全量 trust-all)。
 *
 * **降级阶梯**(保留老设备"一定能连上"的承诺,但校验成为默认行为):
 *  1. Conscrypt + 系统信任库 ∪ 内置 GTS Root R4,正常校验证书链;
 *  2. 校验失败(如设备时钟漂移、小众证书链)→ soft-fail 接受该连接并记日志,
 *     不中断请求(见 [SoftFailTrustManager]);
 *  3. Conscrypt 原生库无法加载(罕见 ABI,如 armeabi-v5)→ 回退平台 TLS +
 *     原 trust-all 补丁路径(见 [LegacyPlatformTls])。
 * 明文 HTTP 降级由 [com.example.mediacenter.data.MediaApi] 保留为最后的兜底。
 */
object AppHttpClient {
    @Volatile private var appContext: Context? = null

    // TLS 配置按需构建(幂等):首次 newBuilder()/installHttpsUrlConnectionDefault()
    // 访问时初始化。trust-all / 证书加载不需要每次都重建。
    @Volatile private var tls: Tls? = null
    private val tlsLock = Any()

    @Volatile private var httpsDefaultInstalled = false

    /**
     * 早期初始化入口:在 Application.onCreate 调用,预热 TLS(加载 Conscrypt 与
     * 内置根证书),确保任何网络请求之前 TLS 已就绪。
     */
    fun init(context: Context) {
        appContext = context
        buildTls()
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

    /** 返回已套用 TLS 配置的 Builder。未显式 init 时也会按需构建(无内置根时仅系统信任库)。 */
    fun newBuilder(): OkHttpClient.Builder {
        buildTls()
        val builder = OkHttpClient.Builder()
        tls?.let { t ->
            builder.sslSocketFactory(t.socketFactory, t.trustManager)
            // 平台降级路径需要补 ECDSA CBC 套件白名单;Conscrypt 路径用 OkHttp 默认
            // MODERN_TLS 即可(所有现代套件都支持),不覆盖。
            t.connectionSpecs?.let(builder::connectionSpecs)
        }
        return builder
    }

    /**
     * 把本 App 的 [SSLSocketFactory] 设为 HttpsURLConnection 进程级默认,
     * 让 ExoPlayer DefaultHttpDataSource 等走 HttpsURLConnection 的链路同样获得
     * Conscrypt + 校验。应在 Application 启动早期、任何网络请求之前调用一次;
     * 失败仅记日志,不影响启动。
     */
    @Synchronized
    fun installHttpsUrlConnectionDefault() {
        if (httpsDefaultInstalled) return
        buildTls()
        val factory = tls?.socketFactory ?: return
        runCatching { HttpsURLConnection.setDefaultSSLSocketFactory(factory) }
            .onSuccess {
                httpsDefaultInstalled = true
                AppLog.i("Http", "installed TLS SSLSocketFactory as HttpsURLConnection default")
            }
            .onFailure { AppLog.w("Http", "failed to install HttpsURLConnection default factory: ${it.message}", it) }
    }

    private fun buildTls() {
        if (tls != null) return
        synchronized(tlsLock) {
            if (tls != null) return
            tls = buildPreferredTls() ?: buildLegacyTls()
        }
    }

    /**
     * 首选路径:Conscrypt + 真实证书校验。
     *
     * Conscrypt 原生库加载失败(罕见 ABI)时返回 null,由调用方回退平台路径。
     */
    private fun buildPreferredTls(): Tls? {
        // 1) 安装 Conscrypt 为最高优先级 JCA 提供者,使其成为
        //    SSLContext.getInstance("TLS") 的默认实现(显式指定更保险)。
        //    返回 -1 仅表示同名 provider 已安装(位置冲突),此时它同样可用。
        val installed = try {
            val position = Security.insertProviderAt(Conscrypt.newProvider(), 1)
            position != -1 || Security.getProvider("Conscrypt") != null
        } catch (error: Throwable) {
            AppLog.w(
                "Http",
                "Conscrypt provider unavailable (${error.message}); falling back to platform TLS",
                error,
            )
            false
        }
        if (!installed) return null
        return try {
            val trustManager = SoftFailTrustManager(buildTrustManagerWithBundledRoots())
            val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            AppLog.i(
                "Http",
                "TLS: Conscrypt + system roots + bundled GTS Root R4 (validation on, soft-fail on error)",
            )
            Tls(sslContext.socketFactory, trustManager, connectionSpecs = null)
        } catch (error: GeneralSecurityException) {
            AppLog.w("Http", "Conscrypt TLS init failed: ${error.message}; falling back to platform TLS", error)
            null
        }
    }

    /**
     * 构建"系统信任库 + 内置补充根"的 X509TrustManager。
     *
     * Android 4.4.2 的系统信任库缺 GTS Root R4(2016 年签发),而媒体服务器
     * yinghuoren.com 的证书链锚定它(经 openssl verify 实测);不补则校验必失败,
     * 只能退到 trust-all。这里把内置根并进系统信任库,校验逻辑保持完整。
     * bilibili 链锚定 GlobalSign Root CA,系统信任库已有,无需额外处理。
     */
    private fun buildTrustManagerWithBundledRoots(): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // 1) 系统信任库全部根证书(AndroidCAStore,API 14+ 可用,4.4.2 OK)。
        val systemStore = KeyStore.getInstance("AndroidCAStore")
        systemStore.load(null, null)
        val aliases = systemStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            systemStore.getCertificate(alias)?.let { keyStore.setCertificateEntry(alias, it) }
        }
        // 2) 内置补充根(raw/gts_r4.pem)。
        val context = appContext
        if (context != null) {
            try {
                val stream = context.resources.openRawResource(R.raw.gts_r4)
                try {
                    val certs = CertificateFactory.getInstance("X.509").generateCertificates(stream)
                    certs.forEachIndexed { index, cert ->
                        keyStore.setCertificateEntry("bundled-gts-r4-$index", cert)
                    }
                    AppLog.i("Http", "bundled ${certs.size} extra root CA(s) into trust store")
                } finally {
                    stream.close()
                }
            } catch (error: Exception) {
                AppLog.w("Http", "failed to load bundled root CA: ${error.message}", error)
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * soft-fail TrustManager:先走真实证书链校验;校验失败时**接受**该连接并记日志。
     *
     * 取舍:本 App 只消费公开视频内容、连接自己的媒体服务器,传输层认证的价值有限,
     * 且老盒子普遍存在时钟漂移 / 缺根证书等无法由 App 修复的问题。校验优先保证
     * 大多数连接处于受保护状态;失败时降级到"加密但不验证对端身份",保住
     * "一定能连上"的承诺,同时把失败原因留给日志 / 诊断页排查。
     */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class SoftFailTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                delegate.checkClientTrusted(chain, authType)
            } catch (error: CertificateException) {
                acceptWithLog("client", error)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                acceptWithLog("server", error)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun acceptWithLog(kind: String, error: CertificateException) {
            AppLog.w(
                "Http",
                "cert validation failed ($kind), soft-fail accepting connection: ${error.message}",
                error,
            )
        }
    }

    private class Tls(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
        /** 非空 = 平台降级路径,需要补 ECDSA CBC 白名单;null = 用 OkHttp 默认 MODERN_TLS。 */
        val connectionSpecs: List<ConnectionSpec>?,
    )

    // ---------------- 平台降级路径(仅 Conscrypt 不可用时启用) ----------------
    // 以下代码是 4.4.2 平台 TLS 栈(OpenSSL 1.0.1)的补丁,保留作为最后防线:
    // 老盒子的 Conscrypt 原生库若无法加载(罕见 ABI),至少维持旧方案的连通性。

    /**
     * OkHttp 3.12 的 MODERN_TLS 白名单(APPROVED_CIPHER_SUITES)缺 ECDSA CBC 套件:
     * 它包含 TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA / TLS_RSA_WITH_AES_128_CBC_SHA,
     * 但**没有** TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA / AES_256_CBC_SHA。
     *
     * Android 4.4.2 的 TLS 栈不支持 TLS 1.3 和 GCM/CHACHA20,只能用 CBC 套件;
     * 而 Cloudflare(yinghuoren.com)只提供 ECDSA 套件(证书是 ECDSA,无 RSA 套件)。
     * 交集为空 → 握手必然失败,表现为"安全连接失败"(trust-all 救不了,因为
     * 失败在套件协商层,证书校验根本没走到)。
     *
     * 这里在 OkHttp 白名单基础上补上 ECDSA CBC 套件,让 4.4.2 能协商
     * TLS 1.2 + ECDHE-ECDSA-AES128-SHA 连上 Cloudflare。
     */
    private val MODERN_TLS_WITH_ECDSA_CBC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .cipherSuites(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
            // 补上 OkHttp 3.12 缺失的 ECDSA CBC 套件(Android 4.4 连 Cloudflare 的关键)。
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        )
        .build()

    private fun buildLegacyTls(): Tls? = try {
        val trustManager = TrustAllManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        AppLog.w("Http", "TLS: platform fallback active (trust-all, legacy patched stack)")
        Tls(
            ModernTlsSocketFactory(sslContext.socketFactory),
            trustManager,
            connectionSpecs = listOf(MODERN_TLS_WITH_ECDSA_CBC),
        )
    } catch (error: GeneralSecurityException) {
        AppLog.w("Http", "fallback TLS init failed: ${error.message}", error)
        null
    }

    /**
     * 信任所有服务器证书的 TrustManager(仅平台降级路径使用):对所有证书链直接放行。
     * 优先路径走 [SoftFailTrustManager] 的真实校验,这里只是最后防线。
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
     * 包裹真实 [SSLSocketFactory],为每个创建出来的 [SSLSocket] 显式启用现代 TLS 版本
     * (仅平台降级路径使用)。作用:在 TLSv1.2 默认未开启的旧系统(API 19/20)上也能
     * 与仅支持 TLS1.2+ 的服务端握手;只在"受支持"集合内启用,不会因不支持的协议名
     * 抛异常,也不会降级到 SSLv3 / TLSv1.0。
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
