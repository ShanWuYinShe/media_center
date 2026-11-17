package com.example.mediacenter.player

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.database.ExoDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.config.BiliCdnHeaders
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 老版播放媒体磁盘缓存 + 首段预取(与新版 media_app 的 PlaybackMediaCache 同思路,
 * 基于 ExoPlayer 2.18.7 的 upstream.cache 包;老盒子内存紧张,容量减半)。
 *
 * - 解析成功后预取头部 768KB(老版音视频合一,moov 可能还在文件末尾,同样补尾部
 *   2MB,由 Range 探测总长决定);
 * - 播放数据源套 CacheDataSource:命中读磁盘,未命中/损坏自动回退网络;默认
 *   write-through,首播边播边写缓存,重看/续播直接命中;
 * - LRU 128MB,放 cacheDir;任何异常静默,不影响播放。
 */
internal object LegacyPlaybackMediaCache {
    private const val CACHE_DIR_NAME = "playback_cache"
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val HEAD_BYTES = 768L * 1024
    private const val TAIL_BYTES = 2L * 1024 * 1024
    private const val FRAGMENT_BYTES = 2L * 1024 * 1024
    private const val PREFETCH_TTL_MS = 5 * 60 * 1000L
    private const val MAX_PREFETCH_KEYS = 32
    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val PROBE_TIMEOUT_MS = 3_000

    @Volatile
    private var cache: SimpleCache? = null

    /** "<url>#<position>" -> 最后一次预取开始时间。 */
    private val prefetchedAt = ConcurrentHashMap<String, Long>()

    fun cache(context: Context): SimpleCache? {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: runCatching {
                val appContext = context.applicationContext
                SimpleCache(
                    File(appContext.cacheDir, CACHE_DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    ExoDatabaseProvider(appContext),
                )
            }.onFailure {
                AppLog.w("Player", "legacy playback cache init failed: ${it.message}", it)
            }.getOrNull()?.also {
                AppLog.i("Player", "legacy playback cache ready dir=${CACHE_DIR_NAME}")
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

    /** 解析成功后预热头部/尾部;失败静默。 */
    fun prefetchInitialBytes(context: Context, videoUrl: String, hasAudioTrack: Boolean) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val cache = cache(appContext) ?: return@launch
            if (markPrefetch(cache, videoUrl, 0L, HEAD_BYTES)) {
                prefetchSpan(cache, videoUrl, 0L, HEAD_BYTES, "head")
            }
            if (hasAudioTrack) return@launch
            val totalBytes = probeContentLength(videoUrl) ?: return@launch
            val tailBytes = minOf(TAIL_BYTES, totalBytes)
            if (totalBytes > tailBytes + HEAD_BYTES &&
                markPrefetch(cache, videoUrl, totalBytes - tailBytes, tailBytes)) {
                prefetchSpan(cache, videoUrl, totalBytes - tailBytes, tailBytes, "tail")
            }
        }
    }

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
        if (prefetchedAt.size > MAX_PREFETCH_KEYS) {
            prefetchedAt.entries
                .sortedBy { it.value }
                .take(prefetchedAt.size - MAX_PREFETCH_KEYS)
                .forEach { prefetchedAt.remove(it.key) }
        }
        return length > 0
    }

    private suspend fun prefetchSpan(
        cache: SimpleCache,
        url: String,
        position: Long,
        length: Long,
        tag: String,
    ) = withContext(Dispatchers.IO) {
        val startedAtMs = System.currentTimeMillis()
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory())
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Player", "media prefetch $tag failed: ${e.message}")
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun upstreamFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(BiliCdnHeaders.getValue("User-Agent"))
            .setDefaultRequestProperties(BiliCdnHeaders - "User-Agent")
            .setAllowCrossProtocolRedirects(true)

    private fun probeContentLength(url: String): Long? = runCatching {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.setRequestProperty("Range", "bytes=0-0")
            BiliCdnHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}