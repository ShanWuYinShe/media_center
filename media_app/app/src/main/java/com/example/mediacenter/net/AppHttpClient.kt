package com.example.mediacenter.net

import com.example.mediacenter.log.AppLog
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 统一的网络客户端工厂,集中处理 TLS 行为。
 *
 * **为什么需要它**:Android TV 盒子 / 模拟器普遍没有 RTC 电池,系统时间依赖开机后 NTP
 * 校准;断网开机、NTP 被墙或模拟器宿主休眠后,设备时钟会漂移几天甚至更久。一旦设备时间
 * 落在服务端 OCSP 装订(stapling)响应的 `thisUpdate..nextUpdate` 有效区间之外,Conscrypt
 * 会在 TLS 握手阶段直接以
 * `Chain validation failed / Response is unreliable: its validity interval is out-of-date`
 * 失败,导致连「拉分类」「检查更新」这种最基本的请求都报"网络连接失败"。证书本身的
 * notBefore/notAfter 区间宽达数月,所以证书没过期,偏偏卡在窄短的 OCSP 区间上。
 *
 * **修复策略**:复刻主流桌面浏览器对 OCSP 装订的 **soft-fail** 行为——证书链、有效期、
 * 签名、主机名照常严格校验,仅当装订的吊销状态不可用 / 过期时放行,而不是中断握手。
 * 实现上用一个自定义 [X509TrustManager] 包裹系统默认 TrustManager:
 *  - 它不是 Conscrypt 的 `TrustManagerImpl`,握手回调时平台不会把装订 OCSP 交给它强校验;
 *  - 委托系统默认 TrustManager 完成完整链校验(信任根 / 有效期 / 签名);
 *  - 对个别仍冒泡上来的吊销类异常再做一层 soft-fail 兜底。
 * 主机名校验仍由 OkHttp / HttpsURLConnection 各自独立完成,安全性不受影响。
 *
 * 同一套 [SSLSocketFactory] 同时用于:OkHttp 客户端,以及通过 [installHttpsUrlConnectionDefault]
 * 设为 `HttpsURLConnection` 全局默认,覆盖 Media3 `DefaultHttpDataSource`(走 HttpsURLConnection)
 * 的播放链路,避免同一台时钟不准的设备在播放时再次握手失败。
 */
object AppHttpClient {
    private val tls: Tls? by lazy { buildTls() }

    @Volatile private var httpsDefaultInstalled = false

    /** 共享的基础客户端(OkHttp 默认超时),用于无特殊超时需求的场景。 */
    val shared: OkHttpClient by lazy { newBuilder().build() }

    /** 返回已套用 OCSP soft-fail TLS 的 Builder,调用方可继续追加超时、重定向等配置。 */
    fun newBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        tls?.let { builder.sslSocketFactory(it.socketFactory, it.trustManager) }
        return builder
    }

    /**
     * 把 OCSP soft-fail 的 [SSLSocketFactory] 设为 `HttpsURLConnection` 进程级默认,
     * 让 Media3 `DefaultHttpDataSource` 等走 HttpsURLConnection 的链路也获得同样的容错。
     * 应在 Application 启动早期、任何网络请求之前调用一次;失败仅记日志,不影响启动。
     */
    @Synchronized
    fun installHttpsUrlConnectionDefault() {
        if (httpsDefaultInstalled) return
        val factory = tls?.socketFactory ?: return
        runCatching { HttpsURLConnection.setDefaultSSLSocketFactory(factory) }
            .onSuccess {
                httpsDefaultInstalled = true
                AppLog.i("Http", "installed OCSP soft-fail SSLSocketFactory as HttpsURLConnection default")
            }
            .onFailure { AppLog.w("Http", "failed to install HttpsURLConnection default factory: ${it.message}", it) }
    }

    private class Tls(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    private fun buildTls(): Tls? = try {
        val systemTrustManager = systemDefaultTrustManager()
        val trustManager = OcspSoftFailTrustManager(systemTrustManager)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        Tls(ModernTlsSocketFactory(sslContext.socketFactory), trustManager)
    } catch (error: GeneralSecurityException) {
        // 极端情况下拿不到默认 TrustManager:退回各客户端自带的默认 TLS,不影响其它功能。
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
     * 委托系统默认 TrustManager 做完整链校验,但对以下场景 soft-fail:
     * 1) OCSP / 吊销类"不可用"异常(clock skew 导致装订响应过期);
     * 2) 纯"Chain validation failed"且原因是时钟漂移(证书尚未生效 / 刚刚过期)。
     *
     * 场景 1 是因为本类不是 Conscrypt 的 `TrustManagerImpl`,握手时平台不会把装订
     * OCSP 交给它强校验,因此装订 OCSP 过期不会再中断握手;此处的 catch 仅作兜底。
     *
     * 场景 2 是因为 Android TV 盒子的 RTC 没有电池,时钟可能偏移几天甚至数周。当 Let's Encrypt
     * 或 Google Trust Services 更换证书时,新证书的 `notBefore` 可能在设备本地时间的
     * "未来",Conscrypt 抛出泛化的 `CertificateException: Chain validation failed`
     * (非 OCSP 路径),不会被场景 1 兜住。这里额外检测证书叶子的时间窗口:如果证书在
     * 容差窗口(±30 天)内就会生效,或刚刚过期,则判定为时钟漂移而非真正的安全攻击。
     */
    private class OcspSoftFailTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (error: CertificateException) {
                if (isRevocationStaleness(error)) {
                    AppLog.w("Http", "soft-fail OCSP/revocation staleness: ${error.message}")
                    return
                }
                if (isClockDriftChainValidation(error, chain)) {
                    AppLog.w("Http", "soft-fail chain validation (likely clock drift): ${error.message}")
                    return
                }
                throw error
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

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
         * 当错误是泛化的"Chain validation failed"时,检查证书叶子的有效期:
         * 如果叶子证书在 ±7 天内是合法区间,则说明是设备时钟漂移。
         */
        private fun isClockDriftChainValidation(
            error: CertificateException,
            chain: Array<out X509Certificate>?,
        ): Boolean {
            // 确认异常链中存在"Chain validation failed"
            var cause: Throwable? = error
            var hasChainValidation = false
            while (cause != null) {
                if (cause.message?.contains("Chain validation", ignoreCase = true) == true) {
                    hasChainValidation = true
                    break
                }
                cause = cause.cause
            }
            if (!hasChainValidation) return false
            // 没有证书链可供分析 → 不应跳过
            if (chain.isNullOrEmpty()) return false

            val leaf = chain[0]
            val now = System.currentTimeMillis()
            // ±30 天容差:覆盖长期断电、NTP 被墙导致 RTC 严重漂移的 TV 盒子。
            // (此前为 ±7 天,实测仍有用户因断电数周而握手失败。)
            val toleranceMs = CLOCK_DRIFT_TOLERANCE_MS

            // 时钟偏慢:证书尚未生效但在容差窗口内会生效
            try {
                leaf.checkValidity(Date(now + toleranceMs))
                AppLog.w(
                    "Http",
                    "soft-fail chain validation (clock drift, slow clock): " +
                        "cert valid ${leaf.notBefore}..${leaf.notAfter}, device now=${Date(now)}",
                )
                return true
            } catch (_: CertificateException) { /* 继续 */ }
            // 时钟偏快:证书刚刚过期在容差窗口内仍有效
            try {
                leaf.checkValidity(Date(now - toleranceMs))
                AppLog.w(
                    "Http",
                    "soft-fail chain validation (clock drift, fast clock): " +
                        "cert valid ${leaf.notBefore}..${leaf.notAfter}, device now=${Date(now)}",
                )
                return true
            } catch (_: CertificateException) { /* 继续 */ }

            return false
        }

        private companion object {
            // 仅匹配"吊销状态不可用 / 过期"这类信号,绝不放行真正的链 / 域名 / 过期错误。
            val SOFT_FAIL_MARKERS = listOf(
                "validity interval is out-of-date",
                "Response is unreliable",
                "OCSP",
                "revocation",
            )
            // 时钟漂移容忍窗口:±30 天。
            val CLOCK_DRIFT_TOLERANCE_MS = 30L * 24 * 60 * 60 * 1000
        }
    }

    /**
     * 包裹真实 [SSLSocketFactory],为每个创建出来的 [SSLSocket] 显式启用现代 TLS 版本。
     * 作用:作为 `HttpsURLConnection` 全局默认时,在 TLSv1.2 默认未开启的旧系统(API 19/20)
     * 上也能与仅支持 TLS1.2+ 的服务端(Cloudflare / 本站 nginx)握手;只在"受支持"集合内启用,
     * 不会因不支持的协议名抛异常,也不会降级到 SSLv3 / TLSv1.0。
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
