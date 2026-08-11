package com.example.mediacenter.net

import com.example.mediacenter.log.AppLog
import java.security.Security
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt

/**
 * 统一的网络客户端工厂。
 *
 * **为什么需要 Conscrypt**:Android 老系统自带 SSL Provider 不支持部分站点(如
 * yinghuoren.com 经 Cloudflare/nginx 的现代 TLS 配置)的握手,表现为"网络连接失败"。
 * Conscrypt 基于 BoringSSL,作为 JCE Provider 注入进程后,OkHttp、HttpsURLConnection
 * 以及 Media3 `DefaultHttpDataSource`(走 HttpsURLConnection)的播放链路会统一走
 * Conscrypt 的现代 TLS 实现,握手兼容性问题随之消除。
 *
 * **安装时机**:Application 启动早期、任何网络请求前调用 [installConscryptProvider],
 * 把 Conscrypt Provider 插到 `Security` 列表首位(优先级最高),之后所有客户端自动
 * 采用;OkHttp 自身和 HttpsURLConnection 都不再需要额外配置 SSL。
 */
object AppHttpClient {
    @Volatile private var providerInstalled = false

    /** 共享的基础客户端(OkHttp 默认超时),用于无特殊超时需求的场景。 */
    val shared: OkHttpClient by lazy { newBuilder().build() }

    /** 返回默认配置的 Builder,调用方可继续追加超时、Cache、拦截器等配置。 */
    fun newBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()

    /**
     * 把 Conscrypt 安装为进程级首选 JCE Provider。
     *
     * Provider 插入 `Security` 首位后,OkHttp(经默认 SSLSocketFactory)、
     * `HttpsURLConnection`(经 `SSLContext.getDefault`)以及基于它的 Media3
     * `DefaultHttpDataSource` 都会自动采用 Conscrypt,无需逐个客户端配置。
     *
     * 幂等:重复调用不会重复插入。失败仅记日志,各客户端退回系统默认 Provider,
     * 不阻断启动(最坏只是回到此前的握手兼容状态)。
     *
     * 应在 Application 启动早期、任何网络请求之前调用一次。
     */
    @Synchronized
    fun installConscryptProvider() {
        if (providerInstalled) return
        runCatching {
            val provider = Conscrypt.newProvider()
            // 插到首位:优先级高于系统自带 Provider,保证 TLS 走 Conscrypt。
            Security.insertProviderAt(provider, 1)
            providerInstalled = true
            AppLog.i("Http", "installed Conscrypt as preferred JCE provider")
        }.onFailure {
            AppLog.w("Http", "failed to install Conscrypt provider: ${it.message}", it)
        }
    }
}
