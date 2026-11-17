package com.example.mediacenter.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.BILI_CDN_HEADERS
import com.example.mediacenter.util.BackgroundWork
import com.example.mediacenter.util.DevicePerformance
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放媒体磁盘缓存 + 首段预取。
 *
 * 起播埋点显示 prepared 到 first frame 的等待几乎全在拉首段媒体(CDN 的 DNS/TCP/TLS
 * + 首字节)。解析成功后把首段前 [PREFETCH_BYTES] 写进 Media3 [SimpleCache],播放页用
 * [CacheDataSource] 读同一 URL 时直接命中缓存，不依赖网络握手；缓存未命中/损坏时
 * 走 [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] 自动回退网络，不影响播放。
 *
 * 设计取舍:
 * - 缓存只读用于播放(不额外写盘,低端盒子避免播放时 IO),写入只发生在预取;
 * - 预取只取第一段视频 + 独立音轨(YouTube DASH 首帧需要音视频两轨都就绪);
 * - HLS/DASH 清单与 data: URL 不预取(清单小、分段未知);多段 durl 取第一段;
 * - 同 URL 5 分钟内只预取一次,最多保留 [MAX_PREFETCH_KEYS] 个记录;
 * - 失败静默:预取只是加速,任何异常都不能影响正常播放。
 */
internal object PlaybackMediaCache {
    private const val CACHE_DIR_NAME = "playback_cache"
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val LOW_END_MAX_BYTES = 128L * 1024 * 1024
    private const val PREFETCH_BYTES = 768L * 1024
    private const val FRAGMENT_BYTES = 256L * 1024
    private const val PREFETCH_TTL_MS = 5 * 60 * 1000L
    private const val MAX_PREFETCH_KEYS = 32
    private const val READ_BUFFER_BYTES = 64 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    /** URL -> 最后一次预取开始时间(忽略签名 query 变化时仍按完整 URL 区分)。 */
    private val prefetchedAt = ConcurrentHashMap<String, Long>()

    /** 进程启动后台预热缓存单例(SimpleCache 首次构造要打开 DB/扫描目录)。 */
    fun warmUp(context: Context) {
        cache(context.applicationContext)
    }

    fun cache(context: Context): SimpleCache? {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: runCatching {
                val appContext = context.applicationContext
                val dir = File(appContext.cacheDir, CACHE_DIR_NAME)
                val maxBytes = if (DevicePerformance.isLowEnd) LOW_END_MAX_BYTES else MAX_BYTES
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(maxBytes),
                    StandaloneDatabaseProvider(appContext),
                )
            }.onFailure {
                AppLog.w("Player", "playback cache init failed: ${it.message}", it)
            }.getOrNull()?.also {
                AppLog.i("Player", "playback cache ready dir=${CACHE_DIR_NAME}")
                cache = it
            }
        }
    }

    /** 播放用 DataSource:优先读缓存,未命中回退 [upstream];缓存异常时忽略缓存。 */
    fun cacheDataSourceFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cache = cache(context) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 解析成功后预热首段媒体;失败静默。 */
    fun prefetchInitialBytes(context: Context, result: PlaybackResult) {
        val targets = prefetchTargets(result)
        if (targets.isEmpty()) return
        val appContext = context.applicationContext
        BackgroundWork.scope.launch {
            val cache = cache(appContext) ?: return@launch
            targets.forEach { url ->
                if (markPrefetch(url, cache)) {
                    prefetchOne(appContext, cache, url)
                }
            }
        }
    }

    /** 该 URL 是否需要预取:近期已预取过或已缓存前缀则跳过。 */
    private fun markPrefetch(url: String, cache: SimpleCache): Boolean {
        val now = System.currentTimeMillis()
        val last = prefetchedAt[url]
        if (last != null && now - last < PREFETCH_TTL_MS) return false
        if (runCatching { cache.isCached(url, 0L, 1L) }.getOrDefault(false)) {
            prefetchedAt[url] = now
            return false
        }
        prefetchedAt[url] = now
        trimPrefetched(now)
        return true
    }

    private fun trimPrefetched(now: Long) {
        if (prefetchedAt.size <= MAX_PREFETCH_KEYS) return
        prefetchedAt.entries
            .filter { now - it.value >= PREFETCH_TTL_MS }
            .forEach { prefetchedAt.remove(it.key) }
        if (prefetchedAt.size > MAX_PREFETCH_KEYS) {
            prefetchedAt.entries
                .sortedBy { it.value }
                .take(prefetchedAt.size - MAX_PREFETCH_KEYS)
                .forEach { prefetchedAt.remove(it.key) }
        }
    }

    private suspend fun prefetchOne(context: Context, cache: SimpleCache, url: String) =
        withContext(Dispatchers.IO) {
            val startedAtMs = System.currentTimeMillis()
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(prefetchUpstreamFactory())
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES),
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(url)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .setLength(PREFETCH_BYTES)
                .build()
            try {
                dataSource.open(dataSpec)
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var readBytes = 0L
                while (readBytes < PREFETCH_BYTES) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    readBytes += read
                }
                val cached = runCatching { cache.getCachedBytes(url, 0L, PREFETCH_BYTES) }
                    .getOrDefault(0L)
                AppLog.i(
                    "Perf",
                    "media prefetch ${cached / 1024}KB cached in " +
                        "${System.currentTimeMillis() - startedAtMs}ms " +
                        "(read=${readBytes / 1024}KB)",
                )
            } catch (e: Exception) {
                AppLog.w("Player", "media prefetch failed: ${e.message}")
            } finally {
                runCatching { dataSource.close() }
            }
        }

    private fun prefetchUpstreamFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(BILI_CDN_HEADERS.getValue("User-Agent"))
            .setDefaultRequestProperties(BILI_CDN_HEADERS - "User-Agent")
            .setAllowCrossProtocolRedirects(true)

    /** 纯逻辑:待预取的直链(视频首段 + 独立音轨),播放页解码首帧需要音视频都就绪。 */
    internal fun prefetchTargets(result: PlaybackResult): List<String> {
        val option = result.options.firstOrNull() ?: return emptyList()
        return prefetchTargets(option)
    }

    internal fun prefetchTargets(option: PlaybackOption): List<String> {
        if (!isStreamCacheable(option)) return emptyList()
        val videoUrl = option.segments?.takeIf { it.size > 1 }?.first() ?: option.url
        return listOfNotNull(
            videoUrl.takeIf { it.isNotBlank() },
            option.audioUrl?.takeIf { it.isNotBlank() },
        ).distinct()
    }

    /** HLS/DASH 清单和 data: URL 不预取(中间件/分段地址未知)。 */
    internal fun isStreamCacheable(option: PlaybackOption): Boolean {
        val url = (option.segments?.takeIf { it.size > 1 }?.first() ?: option.url).lowercase()
        if (url.startsWith("data:") || url.startsWith("blob:")) return false
        if (url.contains(".m3u8") || url.contains(".mpd")) return false
        val mime = option.mimeType.orEmpty().lowercase()
        return !mime.contains("mpegurl") && !mime.contains("dash")
    }
}