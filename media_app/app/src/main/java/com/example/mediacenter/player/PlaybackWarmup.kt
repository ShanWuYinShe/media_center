package com.example.mediacenter.player

import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.BILI_CDN_HEADERS
import com.example.mediacenter.util.BackgroundWork
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 起播前 CDN 连接预热。
 *
 * 起播埋点(阶段4)显示:进入播放页后控制器连接 ~35ms、prepare ~120ms,而
 * "prepared → 首帧"的 5s 级等待几乎全在拉取首段媒体(模拟器网络下 CDN 的
 * DNS/TCP/TLS + 首字节延迟占大头)。这里在详情页解析成功后,用与播放器相同的
 * 平台 HttpURLConnection 栈提前建立到直链 CDN 的 TLS 连接(并请求 1KB),
 * 让播放页真正拉流时能复用已建好的连接,缩短首帧等待。
 *
 * 约束:
 * - 只预热门户/主机,不下载媒体内容,流量可忽略;
 * - 同一 URL(忽略签名 query)60s 内只做一次,避免详情页反复进出时刷请求;
 * - 失败静默(不影响解析/播放),只打一条 Perf 观察日志。
 */
internal object PlaybackWarmup {
    private const val TTL_MS = 60_000L
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000
    // Range 请求小到能完整读完并归还连接池,又不至于触发 CDN 大文件传输。
    private const val WARM_RANGE_BYTES = 1023
    private const val MAX_RECENT = 32

    private val recent = ConcurrentHashMap<String, Long>()

    /** 解析成功后调用:后台预热首段直链(多段取第一段)与独立音轨。 */
    fun warmup(result: PlaybackResult) {
        val urls = warmUrls(result)
        if (urls.isEmpty()) return
        val headers = result.options.firstOrNull()?.headers.orEmpty()
        BackgroundWork.scope.launch {
            for (url in urls) {
                val key = warmKey(url)
                val now = System.currentTimeMillis()
                val last = recent[key]
                if (last != null && now - last < TTL_MS) continue
                recent[key] = now
                trimRecent()
                runCatching { warmOne(url, headers) }
                    .onFailure { AppLog.w("Warmup", "media warmup failed: ${it.message}") }
            }
        }
    }

    /** 待预热 URL 列表(纯函数,便于单测):直链 + 多段第一段 + 独立音轨。 */
    internal fun warmUrls(result: PlaybackResult): List<String> {
        val option = result.options.firstOrNull() ?: return emptyList()
        return buildList {
            option.url.takeIf { it.isNotBlank() }?.let(::add)
            option.segments?.takeIf { it.size > 1 }?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(::add)
            option.audioUrl?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
    }

    /** 去掉签名 query:同一 CDN 路径不同签名视为同一连接目标。 */
    internal fun warmKey(url: String): String = url.substringBefore('?')

    private suspend fun warmOne(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val startedAtMs = System.currentTimeMillis()
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return@withContext
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-$WARM_RANGE_BYTES")
            BILI_CDN_HEADERS.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            val code = conn.responseCode
            val finalHost = conn.url.host
            if (code in 200..299) {
                // 读完小响应,连接才能归还平台连接池供播放器复用。
                conn.inputStream?.use { input ->
                    val buffer = ByteArray(1024)
                    var total = 0
                    while (total < WARM_RANGE_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                }
            }
            AppLog.i(
                "Perf",
                "media warmup $finalHost in ${System.currentTimeMillis() - startedAtMs}ms (http=$code)",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun trimRecent() {
        if (recent.size <= MAX_RECENT) return
        val now = System.currentTimeMillis()
        // 不用 Map.removeIf:minSdk 23 上 java.util.Collection#removeIf 不可用。
        recent.entries.filter { now - it.value >= TTL_MS }.forEach { recent.remove(it.key) }
        if (recent.size > MAX_RECENT) {
            recent.entries
                .sortedBy { it.value }
                .take(recent.size - MAX_RECENT)
                .forEach { recent.remove(it.key) }
        }
    }
}
