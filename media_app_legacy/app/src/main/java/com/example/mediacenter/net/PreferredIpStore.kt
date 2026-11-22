package com.example.mediacenter.net

import android.content.Context
import com.example.mediacenter.log.AppLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns

/**
 * 客户端自测速的 Cloudflare 优选 IP(legacy 变体，minSdk 19)。
 *
 * **要解决的问题**:服务端挂在 Cloudflare 后面，国内运营商 DNS 常把
 * `media.chaoset.com` 解析到绕路/被 QoS 的 anycast 节点。线上实测(电视上报的
 * UA 是 okhttp/3.12.13，即本变体):15 次请求 8 次连不上、成功时首字节 3.7s;
 * 绑定优选 IP 后 15/15 成功、首字节 1.0s。
 *
 * **策略**:服务端下发候选池(/api/v1/preferred-ips，服务端在墙外能稳定拉取
 * 公共优选源)→ 本机 TLS 握手测速排序 → 只对 media.chaoset.com 生效。
 * 任何一步失败都退回内置池/系统 DNS，优选只是加速手段，不能弄坏可用性。
 *
 * 实现上刻意避开 normal 变体的协程/CompletableFuture 写法:API 19 上
 * CompletableFuture 需要脱糖库且老盒子上更脆，这里用固定线程池直接跑。
 */
internal object PreferredIpStore {
    private const val PREFS = "preferred_ip"
    private const val KEY_RANKED = "ranked_ips"
    private const val KEY_PROBED_AT = "probed_at_ms"

    /** 测速结果多久算新鲜(老盒子不做复杂判断，过期也先用)。 */
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    /** 单次 TLS 握手超时:老盒子 CPU 慢，给到 3s。 */
    private const val PROBE_TIMEOUT_MS = 3_000

    /** 并发测速线程数:API 19 电视盒子核数少，3 条并发足够且不抢 UI 线程。 */
    private const val PROBE_THREADS = 3

    private val ranked = AtomicReference<List<String>>(emptyList())

    @Volatile private var appContext: Context? = null

    @Volatile private var refreshStarted = false

    /** 候选池拉取入口，由 Application 注入(生产实现走 MediaApi)。 */
    @Volatile private var poolFetcher: (suspend () -> List<String>?)? = null

    /** 真正生效的目标 host;null 表示不启用优选(域名不在白名单)。 */
    @Volatile private var targetHost: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 给 OkHttp 用的 Dns:只接管目标域名，其余原样交给系统。 */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val target = targetHost
            if (target == null || !PreferredIpPool.matchesHost(hostname, target)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            val current = ranked.get()
            val addresses = PreferredIpPool.toAddresses(current.ifEmpty { PreferredIpPool.BUILTIN })
            return if (addresses.isEmpty()) Dns.SYSTEM.lookup(hostname) else addresses
        }
    }

    /** Application 启动时调用(幂等):先用手上缓存，再后台拉池 + 测速。 */
    fun initAsync(context: Context, baseUrl: String, fetcher: suspend () -> List<String>?) {
        if (refreshStarted) return
        refreshStarted = true
        appContext = context.applicationContext
        poolFetcher = fetcher
        targetHost = PreferredIpPool.resolveTargetHost(baseUrl)
        if (targetHost == null) {
            // 不在白名单(自建/测试域名):不接管任何 DNS，保持改造前行为。
            AppLog.i("PrefIp", "disabled: configured server url host is not whitelisted")
            return
        }
        loadCached()
        scope.launch { refresh() }
    }

    private fun loadCached() {
        val context = appContext ?: return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_RANKED, null) ?: return@runCatching
            val ips = PreferredIpPool.parseRemoteList(stored)
            if (ips.isNotEmpty()) {
                ranked.set(ips)
                val age = System.currentTimeMillis() - prefs.getLong(KEY_PROBED_AT, 0L)
                val stale = if (age > STALE_AFTER_MS) " (stale)" else ""
                AppLog.i("PrefIp", "loaded " + ips.size + " cached ip(s), age=" + (age / 1000) + "s" + stale + ", first=" + ips.first())
            }
        }.onFailure { AppLog.w("PrefIp", "load cached ip failed: " + it.message, it) }
    }

    private suspend fun refresh() {
        val remote = try {
            val fetch = poolFetcher
            if (fetch == null) null else fetch()
        } catch (error: Exception) {
            AppLog.w("PrefIp", "fetch pool failed: " + error.message)
            null
        }
        val candidates = PreferredIpPool.merge(remote.orEmpty())
        if (candidates.isEmpty()) return

        val probes = probeAll(candidates)
        val reachable = probes.filter { it.reachable }
        if (reachable.isEmpty()) {
            AppLog.w("PrefIp", "all " + candidates.size + " candidate ip(s) unreachable, keep previous order")
            return
        }
        val order = PreferredIpPool.rank(reachable) + PreferredIpPool.rank(probes.filterNot { it.reachable })
        ranked.set(order)
        persist(order)
        AppLog.i("PrefIp", "probed " + candidates.size + " ip(s): ok=" + reachable.size + " best=" + order.first())
    }

    /** 分批并发测速(固定线程池，避免依赖 CompletableFuture 脱糖)。 */
    private suspend fun probeAll(candidates: List<String>): List<PreferredIpPool.Probe> =
        withContext(Dispatchers.IO) {
            val results = ArrayList<PreferredIpPool.Probe>(candidates.size)
            var index = 0
            while (index < candidates.size) {
                val chunk = candidates.subList(index, minOf(index + PROBE_THREADS, candidates.size))
                val threads = chunk.map { ip ->
                    val thread = Thread({
                        val probe = probeOne(ip)
                        synchronized(results) { results.add(probe) }
                    }, "prefip-probe")
                    thread.start()
                    thread
                }
                threads.forEach { thread -> runCatching { thread.join(PROBE_TIMEOUT_MS + 1_000L) } }
                index += PROBE_THREADS
            }
            results
        }

    /**
     * 对一个 IP 测速:用它访问本服务的轻量端点，整个请求耗时即排序依据。
     *
     * 用 OkHttp 而不是裸 SSLSocket 手写握手:显式设置 SNI 需要 SNIHostName，
     * 那是 API 24 的类(legacy minSdk 19 会 lint 报错)。OkHttp 3.12 会按 URL
     * 的 host 自动加 SNI、用本 App 的 Conscrypt 栈完成证书校验，还顺带验证了
     * 「这个 IP 真的能服务本域名」。
     */
    private fun probeOne(ip: String): PreferredIpPool.Probe {
        val startedAt = System.nanoTime()
        val client = AppHttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            // URL 仍是域名，只把 DNS 换成本次要测的 IP:SNI/证书校验因此仍按域名走，
            // 与真实业务请求完全同一个栈，测出的耗时才有可比性。
            .dns(PreferredIpPool.singleAddressDns(ip))
            .build()
        val request = okhttp3.Request.Builder()
            .url("https://" + (targetHost ?: PreferredIpPool.DEFAULT_TARGET_HOST) + "/healthz")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                PreferredIpPool.Probe(
                    ip,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    response.isSuccessful,
                )
            }
        } catch (error: Exception) {
            PreferredIpPool.Probe(ip, Long.MAX_VALUE, false)
        }
    }

    private fun persist(order: List<String>) {
        val context = appContext ?: return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RANKED, PreferredIpPool.encode(order))
                .putLong(KEY_PROBED_AT, System.currentTimeMillis())
                .apply()
        }.onFailure { AppLog.w("PrefIp", "persist failed: " + it.message, it) }
    }

    /** 设置/诊断页展示用:当前首选节点 + 测速时间。 */
    fun statusText(): String {
        val order = ranked.get()
        val context = appContext
        val ageMs = if (context != null && order.isNotEmpty()) {
            System.currentTimeMillis() - context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_PROBED_AT, 0L)
        } else {
            0L
        }
        return when {
            order.isEmpty() -> "内置候选（等待测速）"
            ageMs <= 0L -> order.first() + "（" + order.size + " 个候选）"
            else -> order.first() + " · " + order.size + " 个候选 · " + formatProbeAge(ageMs)
        }
    }

    /** 仅测试用。 */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        ranked.set(emptyList())
        refreshStarted = false
        appContext = null
    }
}

/**
 * 测速距今时长的可读文案:刚测速完显示「刚刚测速」,超过 1 小时按小时显示,
 * 避免设置页出现「0 分钟前测速」这种别扭文案(与 normal 端同口径)。
 */
internal fun formatProbeAge(ageMs: Long): String {
    val minutes = ageMs / 60_000
    return when {
        minutes <= 0L -> "刚刚测速"
        minutes < 60L -> minutes.toString() + " 分钟前测速"
        else -> (minutes / 60).toString() + " 小时前测速"
    }
}
