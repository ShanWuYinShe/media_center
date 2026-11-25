package com.example.mediacenter.net

import android.content.Context
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.util.BackgroundWork
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Request

/**
 * 客户端自测速的 Cloudflare 优选 IP。
 *
 * **要解决的问题**:服务端挂在 Cloudflare 后面，国内运营商 DNS 常把
 * `media.chaoset.com` 解析到绕路/被 QoS 的 anycast 节点。实测同一域名走系统
 * DNS:15 次请求 8 次连不上、成功时首字节 3.7s;绑定优选 IP 后 15/15 成功、
 * 首字节 1.0s。App 自己测速挑 IP 比等 DNS 决定节点稳定得多。
 *
 * **分工**:候选池由服务端 `/api/v1/preferred-ips` 下发(服务端在墙外，能稳定
 * 拉取公共优选源;公共源在境内直连普遍超时/被污染)，**测速必须客户端自己做**
 * ——只有客户端知道自己到各节点的真实 RTT。
 *
 * **策略**(全部后台执行，不占启动首帧):
 *  1. 先用手上缓存(SharedPreferences)立刻可用，再后台拉新池 + 重新测速;
 *  2. 对每个候选做一次 TLS 握手(SNI = 目标域名)，握手耗时即 RTT 排序依据;
 *  3. 只对配置的服务端域名生效(白名单校验):OkHttp 的 [Dns] 按 host 分发，
 *     其它域名原样交给系统解析，零副作用;
 *  4. 没有测速结果时用内置兜底池;兜底池也构造不出地址时退回系统 DNS——
 *     优选只是加速手段，任何一步失败都必须保持原有可用性。
 *
 * 只做 IPv4:TLS 握手直连字面量地址，国内 IPv4 优选 IP 的收益已覆盖实测瓶颈。
 */
internal object PreferredIpStore {
    private const val PREFS = "preferred_ip"
    private const val KEY_RANKED = "ranked_ips"
    private const val KEY_PROBED_AT = "probed_at_ms"

    /** 测速结果多久算新鲜:同一网络下 IP 质量按小时~天变化，6 小时足够。 */
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    /** 单次 TLS 握手超时:超过这个时间说明该节点在当前线路不可用。 */
    private const val PROBE_TIMEOUT_MS = 2_500

    /** 候选池并发测速上限:低端盒子线程与 fd 都紧张，分批跑。 */
    private const val PROBE_PARALLELISM = 6

    /** 单次测速任务的等待上限:略大于握手超时，避免个别线程卡死拖住整轮刷新。 */
    private const val PROBE_FUTURE_TIMEOUT_MS = PROBE_TIMEOUT_MS + 2_000L

    /** 服务端候选池端点(与业务同一 api 前缀，由 MediaApi 拼接)。 */
    internal const val POOL_PATH = "preferred-ips"

    private val ranked = AtomicReference<List<String>>(emptyList())

    @Volatile private var appContext: Context? = null

    @Volatile private var refreshStarted = false

    private val refreshMutex = Mutex()

    /**
     * 候选池拉取入口。由 Application 注入(生产实现走 MediaApi 的 /preferred-ips)，
     * 本类不直接依赖仓库单例，便于单测注入。
     */
    @Volatile private var poolFetcher: (suspend () -> List<String>?)? = null

    /**
     * 真正生效的目标 host(来自 BuildConfig.MEDIA_SERVER_URL)。
     * null 表示不启用优选(域名不在白名单，例如自建部署)——此时 Dns 完全透传。
     */
    @Volatile private var targetHost: String? = null

    /** 给 OkHttp 用的 Dns:只接管目标域名，其余原样交给系统。 */
    val dns: Dns = Dns { hostname ->
        val target = targetHost
        if (target == null || !PreferredIpPool.matchesHost(hostname, target)) {
            Dns.SYSTEM.lookup(hostname)
        } else {
            val addresses = PreferredIpPool.toAddresses(effectiveOrder())
            if (addresses.isEmpty()) {
                // 优选结果不可构造(还没测完/全挂):退回系统 DNS，保持原有可用性。
                Dns.SYSTEM.lookup(hostname)
            } else {
                addresses
            }
        }
    }

    private fun effectiveOrder(): List<String> {
        val current = ranked.get()
        return current.ifEmpty { PreferredIpPool.BUILTIN }
    }

    /**
     * Application 启动时调用:[loadCached] 读本地缓存立刻可用，随后后台刷新。
     * 幂等，重复调用只生效一次。
     */
    fun initAsync(context: Context, baseUrl: String, fetcher: suspend () -> List<String>?) {
        if (refreshStarted) return
        refreshStarted = true
        appContext = context.applicationContext
        poolFetcher = fetcher
        targetHost = PreferredIpPool.resolveTargetHost(baseUrl)
        if (targetHost == null) {
            // 不在白名单(自建/测试域名):不接管任何 DNS，保持改造前行为。
            AppLog.i("PrefIp", "disabled: host of the configured server url is not whitelisted")
            return
        }
        loadCached()
        BackgroundWork.scope.launch {
            refreshMutex.withLock {
                refresh()
            }
        }
    }

    /** 读上次测速结果;过期也先用(总比没有强)，后台会刷新。 */
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
                AppLog.i("PrefIp", "loaded ${ips.size} cached ip(s), age=${age / 1000}s${stale}, first=${ips.first()}")
            }
        }.onFailure { AppLog.w("PrefIp", "load cached ip failed: ${it.message}", it) }
    }

    /**
     * 拉服务端候选池 → 合并内置兜底 → 并发 TLS 测速 → 排序落盘。
     * 全程失败静默:任何失败都退回既有顺序/系统 DNS，而不是让播放崩掉。
     *
     * @param fetchPool 拉取候选 IP 列表;返回 null 表示拉取失败(此时只用内置池)。
     */
    private suspend fun refresh() {
        val remote = runCatching { fetchRemotePool() }.getOrNull()
        val candidates = PreferredIpPool.merge(remote.orEmpty())
        if (candidates.isEmpty()) return

        val probes = probeAll(candidates)
        val reachable = probes.filter { it.reachable }
        if (reachable.isEmpty()) {
            // 全不可达:保留旧结果(可能仍比 DNS 好)，不清空。
            AppLog.w("PrefIp", "all ${candidates.size} candidate ip(s) unreachable, keep previous order")
            return
        }
        val order = PreferredIpPool.rank(reachable) +
            PreferredIpPool.rank(probes.filterNot { it.reachable })
        ranked.set(order)
        persist(order)
        AppLog.i(
            "PrefIp",
            "probed ${candidates.size} ip(s): remote=${remote.orEmpty().size} ok=${reachable.size} " +
                "best=${order.firstOrNull()} (${reachable.minOf { it.latencyMs }}ms)",
        )
    }

    /** 拉服务端下发的候选池;拉取入口由 Application 启动时注入(见 [initAsync])。 */
    private suspend fun fetchRemotePool(): List<String>? = withContext(Dispatchers.IO) {
        val fetch = poolFetcher ?: return@withContext null
        runCatching { fetch() }
            .onFailure { AppLog.w("PrefIp", "fetch pool failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * 并发(分批)对候选 IP 做 TLS 握手测速。
     *
     * 用固定线程池 + Future 而不是 CompletableFuture:后者要求 API 24，
     * 本项目 minSdk 23(lint NewApi 会直接判红)。
     */
    private suspend fun probeAll(candidates: List<String>): List<PreferredIpPool.Probe> =
        withContext(Dispatchers.IO) {
            val results = ArrayList<PreferredIpPool.Probe>(candidates.size)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(PROBE_PARALLELISM)
            try {
                for (chunk in candidates.chunked(PROBE_PARALLELISM)) {
                    val futures = chunk.map { ip ->
                        pool.submit(java.util.concurrent.Callable { probeOne(ip) })
                    }
                    for (future in futures) {
                        val probe = runCatching {
                            future.get(PROBE_FUTURE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }.getOrNull()
                        if (probe != null) results.add(probe)
                    }
                }
            } finally {
                // 池子必须回收:测速是一次性任务，常驻线程池会白占老盒子的线程。
                pool.shutdownNow()
            }
            results.ifEmpty { candidates.map { PreferredIpPool.Probe(it, Long.MAX_VALUE, false) } }
        }

    /**
     * 对一个 IP 测速:用它访问本服务的轻量端点，整个请求耗时即排序依据。
     *
     * 用 OkHttp 而不是裸 SSLSocket 手写握手:显式设置 SNI 需要 SNIHostName，
     * 那是 API 24 的类(minSdk 23 会 lint 报错)。OkHttp 会按 URL 的 host 自动
     * 加 SNI、完成证书校验，还顺带验证了「这个 IP 真的能服务本域名」。
     */
    private fun probeOne(ip: String): PreferredIpPool.Probe {
        val startedAt = System.nanoTime()
        val client = AppHttpClient.newBuilder()
            .callTimeout(java.time.Duration.ofMillis(PROBE_FUTURE_TIMEOUT_MS))
            // URL 仍是域名，只把 DNS 换成本次要测的 IP:SNI/证书校验因此仍按域名走，
            // 与真实业务请求完全同一个栈，测出的耗时才有可比性。
            .dns { listOf(java.net.InetAddress.getByName(ip)) }
            .build()
        val request = Request.Builder()
            .url("https://${targetHost ?: PreferredIpPool.DEFAULT_TARGET_HOST}/healthz")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                PreferredIpPool.Probe(
                    ip = ip,
                    latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                    reachable = response.isSuccessful,
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
        }.onFailure { AppLog.w("PrefIp", "persist failed: ${it.message}", it) }
    }

    /**
     * 设置页展示用的状态文本:当前首选节点 + 最近测速距今时间。
     * 优选 IP 的排障入口——"连不上/一直转圈"时先看这里是否拿到了节点。
     */
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
            ageMs <= 0L -> "${order.first()}（${order.size} 个候选）"
            else -> "${order.first()} · ${order.size} 个候选 · ${formatProbeAge(ageMs)}"
        }
    }

    /**
     * 手动触发测速与优选更新并返回最新状态文本。
     */
    suspend fun retest(): String = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            refresh()
        }
        statusText()
    }

    /** 仅测试用:重置进程内状态。 */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        ranked.set(emptyList())
        refreshStarted = false
        appContext = null
    }

    /** 仅测试用:当前排序(未测速时为空)。 */
    @androidx.annotation.VisibleForTesting
    internal fun rankedForTest(): List<String> = ranked.get()

    /** 仅测试用:直接注入排序，跳过测速。 */
    @androidx.annotation.VisibleForTesting
    internal fun setRankedForTest(ips: List<String>) {
        ranked.set(ips)
    }
}

/**
 * 测速距今时长的可读文案:刚测速完显示「刚刚测速」,超过 1 小时按小时显示,
 * 避免设置页出现「0 分钟前测速」这种别扭文案。
 */
internal fun formatProbeAge(ageMs: Long): String {
    val minutes = ageMs / 60_000
    return when {
        minutes <= 0L -> "刚刚测速"
        minutes < 60L -> "$minutes 分钟前测速"
        else -> "${minutes / 60} 小时前测速"
    }
}
