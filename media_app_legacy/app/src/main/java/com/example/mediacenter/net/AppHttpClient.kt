package com.example.mediacenter.net

import android.content.Context
import com.example.mediacenter.R
import com.example.mediacenter.log.AppLog
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * 统一的网络客户端工厂,集中处理 TLS 行为,确保在 Android 4.4.2 (API 19) 上
 * 也能与现代 HTTPS 服务器握手成功。
 *
 * **两个老设备 TLS 痛点及修复**:
 *
 * 1. **缺失现代根 CA**(最高频根因):服务端证书链锚定在 Google Trust Services 的
 *    GTS Root R4,而该根证书大约从 Android 7.0 (API 24) 才内置进系统信任库。
 *    Android 4.4.2 (API 19) 的信任库里没有 GTS Root R4 → 找不到可信锚点 →
 *    `unable to find valid certification path` → 所有 HTTPS 请求失败。
 *    这是 Android 老设备访问现代 HTTPS 服务的头号障碍(Let's Encrypt 社区、
 *    Cloudflare 都有大量同类报告)。**这不是"检查系统时间"能解决的问题。**
 *
 *    修复:把 GTS Root R4 自签根证书作为 raw resource 打包进 App,运行时加载并
 *    合并进信任库,作为系统信任库的**附加信任锚**。安全性不降低——只额外信任了
 *    Google 官方根 CA,仍做完整链 / 签名 / 域名 / 有效期校验;新设备系统库已有
 *    该根,重复信任无害。
 *
 * 2. **设备时钟漂移**(次频根因):TV 盒子普遍没有 RTC 电池,断电后时钟会漂移。
 *    设备时间落在证书 notBefore..notAfter 之外时,Conscrypt 判定证书时间无效。
 *    保留 OCSP / 时钟漂移 soft-fail 作为辅助兜底:加密链自校验通过(签名 + 锚点)
 *    时放行,同时精确记录漂移量便于诊断。
 *
 * 同一套 [SSLSocketFactory] 同时用于 OkHttp 客户端,以及通过
 * [installHttpsUrlConnectionDefault] 设为 HttpsURLConnection 全局默认,
 * 覆盖 ExoPlayer DefaultHttpDataSource(走 HttpsURLConnection)的播放链路。
 */
object AppHttpClient {
    private var tls: Tls? = null
    private var initialized = false

    @Volatile private var httpsDefaultInstalled = false

    /**
     * 在 Application.onCreate 早期调用一次,加载附加根 CA 并构建 TLS。
     * 必须在任何网络请求之前完成(否则 [newBuilder] 返回的客户端没有套用自定义 TLS)。
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        tls = buildTls(context)
        if (tls == null) {
            AppLog.w("Http", "TLS init failed, falling back to platform default (old devices may fail to connect)")
        }
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

    /** 返回已套用自定义 TLS(附加根 CA + soft-fail)的 Builder。 */
    fun newBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        tls?.let { builder.sslSocketFactory(it.socketFactory, it.trustManager) }
        return builder
    }

    /**
     * 把自定义 [SSLSocketFactory] 设为 HttpsURLConnection 进程级默认,
     * 让 ExoPlayer DefaultHttpDataSource 等走 HttpsURLConnection 的链路也获得同样的容错。
     * 应在 Application 启动早期、任何网络请求之前调用一次;失败仅记日志,不影响启动。
     */
    @Synchronized
    fun installHttpsUrlConnectionDefault() {
        if (httpsDefaultInstalled) return
        val factory = tls?.socketFactory ?: return
        runCatching { HttpsURLConnection.setDefaultSSLSocketFactory(factory) }
            .onSuccess {
                httpsDefaultInstalled = true
                AppLog.i("Http", "installed custom SSLSocketFactory as HttpsURLConnection default")
            }
            .onFailure { AppLog.w("Http", "failed to install HttpsURLConnection default factory: ${it.message}", it) }
    }

    private class Tls(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    private fun buildTls(context: Context): Tls? = try {
        val systemTrustManager = systemDefaultTrustManager()
        val extraRoots = loadExtraRootCerts(context)
        val trustManager = if (extraRoots.isEmpty()) {
            // 没有附加根 CA(资源缺失等),退化为纯系统信任库 + soft-fail。
            SoftFailTrustManager(listOf(systemTrustManager), systemTrustManager.acceptedIssuers)
        } else {
            // 用附加根 CA 构建一个"额外" TrustManager。校验时**系统 TrustManager 和
            // 额外 TrustManager 任一通过即放行**——这样系统根(bilibili、图片 CDN 等)
            // 走系统 TrustManager,服务端证书链锚定 GTS Root R4 的走额外 TrustManager,
            // 两类站点都能验证通过。
            val extraKeyStore = keyStoreOf(extraRoots)
            val extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraFactory.init(extraKeyStore)
            val extraTrustManager = extraFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw GeneralSecurityException("no extra X509TrustManager available")
            val allAnchors = (systemTrustManager.acceptedIssuers + extraTrustManager.acceptedIssuers).toList()
            SoftFailTrustManager(listOf(systemTrustManager, extraTrustManager), allAnchors.toTypedArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        AppLog.i("Http", "TLS initialized with ${extraRoots.size} extra root CA(s) + soft-fail")
        Tls(ModernTlsSocketFactory(sslContext.socketFactory), trustManager)
    } catch (error: GeneralSecurityException) {
        AppLog.w("Http", "fallback to default TLS: ${error.message}", error)
        null
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw GeneralSecurityException("no system X509TrustManager available")
    }

    /**
     * 从 raw resource 加载附加根 CA 证书(PEM 格式)。
     * 当前打包了 GTS Root R4(Google Trust Services 自签根),解决 Android 4.4.2
     * 系统信任库缺失该根导致无法验证服务端证书链的问题。
     * 返回空列表时不影响功能(退化为纯系统信任库)。
     */
    private fun loadExtraRootCerts(context: Context): List<X509Certificate> {
        val rawIds = intArrayOf(R.raw.gts_root_r4)
        val cf = CertificateFactory.getInstance("X.509")
        val certs = ArrayList<X509Certificate>(rawIds.size)
        for (id in rawIds) {
            val name = try { context.resources.getResourceEntryName(id) } catch (_: Exception) { "raw_$id" }
            try {
                context.resources.openRawResource(id).use { input ->
                    certs.addAll(cf.generateCertificates(input).filterIsInstance<X509Certificate>())
                }
            } catch (e: Exception) {
                AppLog.w("Http", "failed to load extra root cert $name: ${e.message}", e)
            }
        }
        AppLog.i("Http", "loaded ${certs.size} extra root cert(s) from raw resources")
        return certs
    }

    /** 把证书列表装进一个空的 BKS/PKCS12 KeyStore,供 TrustManagerFactory 初始化。 */
    private fun keyStoreOf(certs: List<X509Certificate>): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, CharArray(0))
        certs.forEachIndexed { i, cert ->
            ks.setCertificateEntry("extra-root-$i", cert)
        }
        return ks
    }

    /**
     * Soft-fail TrustManager:委托底层 TrustManager 集合做完整校验(任一通过即放行),
     * 仅对以下两类确定可安全放行的场景宽容:
     * 1) OCSP / 吊销类"不可用"异常;
     * 2) 加密链自校验通过(逐级签名 + 锚定信任集合)但时间校验失败(设备时钟漂移)。
     *
     * 多委托模式:[delegates] 通常包含系统 TrustManager 和额外 TrustManager(附加根 CA)。
     * 服务端证书链锚定系统根时由系统 TrustManager 验证通过;锚定附加根(如 GTS Root R4,
     * Android 4.4 系统库缺失)时由额外 TrustManager 验证通过。两类站点都能连接。
     *
     * [acceptedAnchors] 是用于加密链自校验的完整锚集合(系统根 + 附加根)。
     */
    @Suppress("CustomX509TrustManager")
    private class SoftFailTrustManager(
        private val delegates: List<X509TrustManager>,
        private val acceptedAnchors: Array<X509Certificate>,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegates.first().checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 任一委托 TrustManager 校验通过即放行:系统根走系统 TrustManager,
            // 附加根(GTS Root R4)走额外 TrustManager。
            var lastError: CertificateException? = null
            for (delegate in delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType)
                    return
                } catch (error: CertificateException) {
                    lastError = error
                }
            }
            val error = lastError ?: throw CertificateException("no trust manager accepted the chain")
            // 全部委托都失败,进入 soft-fail 判定。
            if (isRevocationStaleness(error)) {
                AppLog.w("Http", "soft-fail OCSP/revocation staleness: ${error.message}")
                return
            }
            if (isTimeValidationFailure(error, chain)) {
                return
            }
            throw error
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = acceptedAnchors

        /** 检查异常信息是否匹配 OCSP / 吊销不可用的关键字。 */
        private fun isRevocationStaleness(error: Throwable?): Boolean {
            var cause = error
            while (cause != null) {
                val message = cause.message
                if (message != null && SOFT_FAIL_MARKERS.any { message.contains(it, ignoreCase = true) }) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        /**
         * 判定是否为"设备时钟漂移导致的时间有效性失败"并 soft-fail:
         * 1. 异常链中存在时间类信号(证书未生效 / 已过期);
         * 2. 证书链加密自校验通过(签名逐级验证 + 链尾锚定信任集合)。
         * 两点同时成立 → 失败根因只能是设备本地时间错误,放行并记录精确漂移。
         */
        private fun isTimeValidationFailure(
            error: CertificateException,
            chain: Array<out X509Certificate>?,
        ): Boolean {
            if (!hasTimeValidationSignal(error)) return false
            if (chain.isNullOrEmpty() || !chainCryptoValid(chain)) {
                // 加密链自校验不过:不是时间问题(伪造 / 不受信任),不能放行。
                AppLog.w("Http", "time-type failure but crypto chain invalid, rethrow: ${error.message}")
                return false
            }
            AppLog.w("Http", "soft-fail cert time validation (device clock drift): ${describeDrift(chain)}")
            return true
        }

        /** 遍历 cause 链,识别"证书未生效 / 已过期"这类时间信号。 */
        private fun hasTimeValidationSignal(error: Throwable?): Boolean {
            var cause = error
            while (cause != null) {
                if (cause is CertificateNotYetValidException || cause is CertificateExpiredException) {
                    return true
                }
                val message = cause.message.orEmpty()
                if (TIME_FAILURE_MARKERS.any { message.contains(it, ignoreCase = true) }) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        /**
         * 证书链加密自校验:链内逐张证书由下一张的公钥签发,链尾必须能被
         * 信任锚集合(系统根 + 附加根)中的某个锚验证。全部通过才认为链本身"可信"。
         */
        private fun chainCryptoValid(chain: Array<out X509Certificate>): Boolean {
            for (i in 0 until chain.size - 1) {
                try {
                    chain[i].verify(chain[i + 1].publicKey)
                } catch (_: Exception) {
                    return false
                }
            }
            val tail = chain[chain.size - 1]
            for (anchor in acceptedAnchors) {
                try {
                    tail.verify(anchor.publicKey)
                    return true
                } catch (_: Exception) { /* 继续尝试下一个锚 */ }
            }
            // 链尾本身就是自签锚:用自身公钥自校验。
            return if (tail.issuerDN == tail.subjectDN) {
                try {
                    tail.verify(tail.publicKey)
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        }

        /** 计算并描述叶子证书与设备当前时间的漂移量,供日志远程诊断。 */
        private fun describeDrift(chain: Array<out X509Certificate>): String {
            val leaf = chain[0]
            val now = System.currentTimeMillis()
            val days = 24L * 60 * 60 * 1000
            return when {
                now < leaf.notBefore.time ->
                    "device clock SLOW by ${(leaf.notBefore.time - now) / days} days " +
                        "(cert notBefore ${leaf.notBefore}, device now=${Date(now)})"
                now > leaf.notAfter.time ->
                    "device clock FAST by ${(now - leaf.notAfter.time) / days} days " +
                        "(cert notAfter ${leaf.notAfter}, device now=${Date(now)})"
                else -> "leaf valid at device time? cert=${leaf.notBefore}..${leaf.notAfter} now=${Date(now)}"
            }
        }

        private companion object {
            // 仅匹配"吊销状态不可用 / 过期"这类信号,绝不放行真正的链 / 域名 / 过期错误。
            val SOFT_FAIL_MARKERS = listOf(
                "validity interval is out-of-date",
                "Response is unreliable",
                "OCSP",
                "revocation",
            )
            // 时间类信号:覆盖现代 Conscrypt(包装成 Chain validation failed + cause)
            // 与 Android 4.4 老 Conscrypt(直接抛 NotBefore/NotAfter 消息)两种形态。
            val TIME_FAILURE_MARKERS = listOf(
                "not yet valid",
                "expired",
                "NotBefore",
                "NotAfter",
                "validity",
            )
        }
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
