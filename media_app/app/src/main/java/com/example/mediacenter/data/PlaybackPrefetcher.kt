package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog
import com.example.mediacenter.util.BackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 播放地址预取:用户停留在详情页时提前解析,点"播放"时直接命中,把解析等待
 * 移出按键后的关键路径——B 站本地解析约 0.5–1.5s,YouTube 服务端解析在冷启动
 * 时可达 25–30s。
 *
 * 设计取舍:
 * - 只在详情页停留 >=1.2s 后触发(见 DetailScreen):快速浏览列表不触发,避免
 *   把 YouTube 冷解析(占用 extractor 并发)浪费在"只看一眼"的视频上;
 * - 键包含 provider/videoId/qn,换 provider 或换清晰度不会误用;
 * - TTL 5 分钟 + 最多 8 条:播放直链有时效,过期结果不可回放;
 * - 与 PlayerViewModel 共享:预取在途时播放页等待同一任务,不重复解析;
 * - 失败静默丢弃,播放页照常重新解析并回填。
 */
object PlaybackPrefetcher {
    private const val MAX_ENTRIES = 8
    private const val TTL_MS = 5 * 60 * 1000L

    private data class Key(val provider: String, val videoId: String, val qn: Int?)
    private data class Entry(val result: PlaybackResult, val fetchedAtMs: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true)
    private val inflight = HashMap<Key, Job>()

    /** 发起预取;已有未过期缓存或在途任务时直接复用。失败只记日志。 */
    fun prefetch(repository: MediaRepositoryApi, videoId: String, qn: Int? = null) {
        if (videoId.isBlank()) return
        val key = keyOf(repository, videoId, qn)
        val alreadyPending = synchronized(lock) {
            cache[key]?.let(::fresh) == true || inflight.containsKey(key)
        }
        if (alreadyPending) return
        val job = BackgroundWork.scope.launch {
            try {
                put(key, repository.resolvePlayback(videoId, qn))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Prefetch", "playback prefetch failed: ${e.message}", e)
            } finally {
                synchronized(lock) { inflight.remove(key) }
            }
        }
        synchronized(lock) { inflight[key] = job }
    }

    /**
     * 播放页取解析结果:命中缓存直接返回(一次性消费);预取在途则等待同一任务;
     * 未命中返回 null,由调用方正常解析。
     */
    suspend fun resolve(repository: MediaRepositoryApi, videoId: String, qn: Int?): PlaybackResult? {
        val key = keyOf(repository, videoId, qn)
        take(key)?.let { return it }
        val job = synchronized(lock) { inflight[key] } ?: return null
        job.join()
        return take(key)
    }

    /** 播放页解析成功后回填,后续切分P/重试可复用。 */
    fun put(repository: MediaRepositoryApi, videoId: String, qn: Int?, result: PlaybackResult) {
        put(keyOf(repository, videoId, qn), result)
    }

    private fun keyOf(repository: MediaRepositoryApi, videoId: String, qn: Int?) =
        Key(repository.currentProvider, videoId, qn)

    private fun take(key: Key): PlaybackResult? {
        val entry = synchronized(lock) { cache.remove(key) } ?: return null
        return if (fresh(entry)) entry.result else null
    }

    private fun put(key: Key, result: PlaybackResult) {
        synchronized(lock) {
            cache[key] = Entry(result, System.currentTimeMillis())
            while (cache.size > MAX_ENTRIES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    private fun fresh(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.fetchedAtMs <= TTL_MS
}
