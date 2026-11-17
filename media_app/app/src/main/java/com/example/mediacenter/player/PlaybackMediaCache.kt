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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放媒体磁盘缓存 + 首段预取。
 *
 * 起播埋点显示 prepared 到 first frame 的等待几乎全在拉首段媒体(CDN 的 DNS/TCP/TLS
 * + 首字节)。解析成功后把媒体头部写进 Media3 [SimpleCache],播放页用
 * [CacheDataSource] 读同一 URL 时直接命中缓存，不依赖网络握手；缓存未命中/损坏时
 * 走 [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] 自动回退网络，不影响播放。
 *
 * 预取范围:
 * - 头部 [HEAD_BYTES]:首帧解码需要的容器头/首批样本(音视频分离时视频、音轨各一份);
 * - 尾部 [TAIL_BYTES]:B 站 durl 是完整 MP4,部分片源 moov 在文件末尾,只缓存头部
 *   时播放器仍要等尾部元数据,因此对「音视频合一的 progressive 流」再预取尾部;
 *   音视频分离(YouTube DASH)的 moov 在头部,不需要尾部。
 *
 * 设计取舍:
 * - 缓存只读用于播放(不额外写盘,低端盒子避免播放时 IO),写入只发生在预取;
 * - HLS/DASH 清单与 data: URL 不预取(清单小、分段未知);多段 durl 取第一段;
 * - 同 URL+位置 5 分钟内只预取一次,最多保留 [MAX_PREFETCH_KEYS] 个记录;
 * - 失败静默:预取只是加速,任何异常都不能影响正常播放。
 */
internal object PlaybackMediaCache {
    private const val CACHE_DIR_NAME = "playback_cache"
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val LOW_END_MAX_BYTES = 128L * 1024 * 1024
    private const val HEAD_BYTES = 768L * 1024
    private const val TAIL_BYTES = 512L * 1024
    // CacheDataSink 建议 fragment >= 2MB;预取 span 本身不大,2MB 让每个 DataSpec 一个 span。
    private const val FRAGMENT_BYTES = 2L * 1024 * 1024
    private const val PREFETCH_TTL_MS = 5 * 60 * 1000L
    private const val MAX_PREFETCH_KEYS = 48
    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val PROBE_TIMEOUT_MS = 3_000

    @Volatile
    private var cache: SimpleCache? = null

    /** "<url>#<position>" -> 最后一次预取开始时间。 */
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
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    AppLog.i("Perf", "playback cache read ${cachedBytesRead / 1024}KB")
                }

                override fun onCacheIgnored(reason: Int) {
                    AppLog.w("Player", "playback cache ignored reason=$reason")
                }
            })
    }

    /** 解析成功后预热首段媒体;失败静默。 */
    fun prefetchInitialBytes(context: Context, result: PlaybackResult) {
        val option = result.options.firstOrNull() ?: return
        if (!isStreamCacheable(option)) return
        val appContext = context.applicationContext
        BackgroundWork.scope.launch {
            val cache = cache(appContext) ?: return@launch
            val videoUrl = (option.segments?.takeIf { it.size > 1 }?.first() ?: option.url)
            // 头部与尾部分头并行:用户在详情页停留时间有限,串行(head→探测→tail)
            // 会让尾部元数据在点播放后才就绪,首帧仍要等网络。
            coroutineScope {
                if (videoUrl.isNotBlank()) {
                    launch { prefetchSpan(cache, videoUrl, 0L, HEAD_BYTES, "head") }
                }
                val audioUrl = option.audioUrl
                if (!audioUrl.isNullOrBlank()) {
                    launch { prefetchSpan(cache, audioUrl, 0L, HEAD_BYTES, "audio") }
                } else if (shouldPrefetchTail(option) && videoUrl.isNotBlank()) {
                    launch {
                        val totalBytes = probeContentLength(videoUrl) ?: return@launch
                        val tailBytes = minOf(TAIL_BYTES, totalBytes)
                        if (totalBytes > tailBytes + HEAD_BYTES) {
                            prefetchSpan(
                                cache,
                                videoUrl,
                                totalBytes - tailBytes,
                                tailBytes,
                                "tail",
                            )
                        }
                    }
                }
            }
        }
    }

    /** 该 URL+位置 是否需要预取:近期已预取过或已缓存则跳过。 */
    private fun markPrefetch(cache: SimpleCache, url: String, position: Long, length: Long): Boolean {
        val key = "$url#$position"
        val now = System.currentTimeMillis()
        val last = prefetchedAt[key]
        if (last != null && now - last < PREFETCH_TTL_MS) return false
        if (runCatching { cache.isCached(url, position, 1L) }.getOrDefault(false)) {
            prefetchedAt[key] = now
            return false
        }
        prefetchedAt[key] = now
        trimPrefetched(now)
        return length > 0
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

    private suspend fun prefetchSpan(
        cache: SimpleCache,
        url: String,
        position: Long,
        length: Long,
        tag: String,
    ) = withContext(Dispatchers.IO) {
        if (!markPrefetch(cache, url, position, length)) return@withContext
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
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            dataSource.open(dataSpec)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var readBytes = 0L
            while (readBytes < length) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                readBytes += read
            }
            AppLog.i(
                "Perf",
                "media prefetch $tag ${readBytes / 1024}KB in " +
                    "${System.currentTimeMillis() - startedAtMs}ms (pos=$position)",
            )
        } catch (e: Exception) {
            AppLog.w("Player", "media prefetch $tag failed: ${e.message}")
        } finally {
            runCatching { dataSource.close() }
            val cachedBytes = runCatching { cache.getCachedBytes(url, position, length) }
                .getOrDefault(0L)
            AppLog.i(
                "Perf",
                "media prefetch $tag cached=${cachedBytes / 1024}KB (pos=$position)",
            )
        }
    }


    private fun prefetchUpstreamFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(BILI_CDN_HEADERS.getValue("User-Agent"))
            .setDefaultRequestProperties(BILI_CDN_HEADERS - "User-Agent")
            .setAllowCrossProtocolRedirects(true)

    /** 探测媒体总长度(用于尾部预取);失败返回 null。 */
    private fun probeContentLength(url: String): Long? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.setRequestProperty("Range", "bytes=0-0")
            BILI_CDN_HEADERS.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val contentRange = connection.getHeaderField("Content-Range")
            contentRange?.substringAfterLast('/')?.toLongOrNull()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

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

    /** 音视频合一的 progressive 流才需要预取尾部(moov 可能在文件末尾)。 */
    internal fun shouldPrefetchTail(option: PlaybackOption): Boolean =
        option.audioUrl.isNullOrBlank()

    /** HLS/DASH 清单和 data: URL 不预取(中间件/分段地址未知)。 */
    internal fun isStreamCacheable(option: PlaybackOption): Boolean {
        val url = (option.segments?.takeIf { it.size > 1 }?.first() ?: option.url).lowercase()
        if (url.startsWith("data:") || url.startsWith("blob:")) return false
        if (url.contains(".m3u8") || url.contains(".mpd")) return false
        val mime = option.mimeType.orEmpty().lowercase()
        return !mime.contains("mpegurl") && !mime.contains("dash")
    }
}