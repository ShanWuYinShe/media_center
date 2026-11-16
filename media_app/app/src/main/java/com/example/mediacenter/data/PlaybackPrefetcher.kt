package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog
import com.example.mediacenter.player.PlaybackMediaCache
import com.example.mediacenter.player.PlaybackWarmup
import com.example.mediacenter.util.BackgroundWork
import com.example.mediacenter.util.DevicePerformance
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
 * - TTL 5 分钟 + 最多 8 条:播放直链有时效,过期结果不可回放;TTL 内可被重复复用
 *   (快速回看不再重复解析);播放错误后的「重试」必须走 invalidate + 强制重新解析,
 *   否则会把同一批失效直链原样还回去(见 PlayerViewModel.resolvePlayback(forceRefresh));
 * - 与 PlayerViewModel 共享:预取在途时播放页等待同一任务,不重复解析;
 * - 失败静默丢弃,播放页照常重新解析并回填。
 */
object PlaybackPrefetcher {
    private val maxEntries: Int = if (DevicePerformance.isLowEnd) 4 else 8
    private const val TTL_MS = 5 * 60 * 1000L

    private data class Key(val provider: String, val videoId: String, val qn: Int?)
    private data class Entry(val result: PlaybackResult, val fetchedAtMs: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true)
    private val inflight = HashMap<Key, Job>()

    /** 发起预取;已有未过期缓存或在途任务时直接复用。失败只记日志。 */
    fun prefetch(
        repository: MediaRepositoryApi,
        videoId: String,
        qn: Int? = null,
        // 自动选档上限:与播放页同一口径,预取的首段媒体才会被播放页真正命中。
        maxInitialQualityQn: Int = Int.MAX_VALUE,
    ) {
        if (videoId.isBlank()) return
        val key = keyOf(repository, videoId, qn)
        val alreadyPending = synchronized(lock) {
            cache[key]?.let(::fresh) == true || inflight.containsKey(key)
        }
        if (alreadyPending) return
        val startedAtMs = System.currentTimeMillis()
        val job = BackgroundWork.scope.launch {
            try {
                val result = repository.resolvePlayback(videoId, qn)
                put(key, result)
                // 解析结果一到就预取首段媒体(详情页停留通常 >=1s,用户点播放前可完成大部分)。
                // 手动选档(qn!=null)要按该档重排,否则预取的媒体不是播放页实际会播的流。
                val tuned = if (qn == null) {
                    PlaybackTuning.preferInitialOption(result, maxInitialQualityQn)
                } else {
                    PlaybackTuning.preferOption(result, qn)
                }
                // 再按「实际会播的档位」额外缓存一份:播放页切分P/重试时按 qn 精确
                // 查询,只缓存 qn=null 的键永远命中不了——多P「下一集」预取此前因此白做。
                tuned.options.firstOrNull()?.qn?.let { playedQn ->
                    put(keyOf(repository, videoId, playedQn), tuned)
                }
                MediaRepositoryHolder.applicationContext?.let { context ->
                    PlaybackMediaCache.prefetchInitialBytes(context, tuned)
                }
                // 解析完成即预热 CDN 连接(阶段4 起播优化):媒体预取走的是
                // PlaybackMediaCache 自己的数据源栈,连接池与 Media3 拉流不共享;
                // 这里用与播放器相同的平台 HttpURLConnection 预热 TLS/DNS,
                // 详情页停留到点播放之间通常有 1s+,首段请求可复用已建连接。
                PlaybackWarmup.warmup(tuned)
                AppLog.i(
                    "Perf",
                    "prefetch ready $videoId qn=${qn ?: "auto"} " +
                        "in ${System.currentTimeMillis() - startedAtMs}ms",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(
                    "Prefetch",
                    "playback prefetch failed: ${e.message} " +
                        "(after ${System.currentTimeMillis() - startedAtMs}ms)",
                    e,
                )
            } finally {
                synchronized(lock) { inflight.remove(key) }
            }
        }
        synchronized(lock) { inflight[key] = job }
    }

    /**
     * 播放页取解析结果:命中缓存直接返回(TTL 内可重复复用,不消费);预取在途
     * 则等待同一任务;未命中返回 null,由调用方正常解析。
     */
    suspend fun resolve(repository: MediaRepositoryApi, videoId: String, qn: Int?): PlaybackResult? {
        val key = keyOf(repository, videoId, qn)
        get(key)?.let { return it }
        // 带 qn 的查询(多P切页/切档重试/播放页重试)若未精确命中,退回同视频的
        // 兼容结果:预取可能按 qn=null(自动档)或另一档写入,但结果里包含该档;
        // 没有这层兜底,多P「下一集」的预取永远命中不了,切页仍要重新解析。
        if (qn != null) compatible(repository, videoId, qn)?.let { return it }
        val job = synchronized(lock) { inflight[key] }
        if (job == null) {
            // 精确 key 没有在途任务时,等同视频任意在途预取落地后再试一次兼容查找。
            if (qn != null) {
                val provider = repository.currentProvider
                val anyJob = synchronized(lock) {
                    inflight.entries.firstOrNull {
                        it.key.provider == provider && it.key.videoId == videoId
                    }?.value
                }
                if (anyJob != null) {
                    anyJob.join()
                    compatible(repository, videoId, qn)?.let { return it }
                }
            }
            return null
        }
        job.join()
        get(key)?.let { return it }
        return if (qn != null) compatible(repository, videoId, qn) else null
    }

    /**
     * 在缓存中找同一视频、且能提供 [qn] 档位的结果:
     * 结果首档就是该档(直接可播),或者结果里存在该档(重排后返回)。找不到返回 null。
     */
    private fun compatible(repository: MediaRepositoryApi, videoId: String, qn: Int): PlaybackResult? {
        val provider = repository.currentProvider
        synchronized(lock) {
            for ((entryKey, entry) in cache) {
                if (entryKey.provider != provider || entryKey.videoId != videoId) continue
                if (!fresh(entry)) continue
                val result = entry.result
                if (result.options.firstOrNull()?.qn == qn) return result
                val tuned = PlaybackTuning.preferOption(result, qn)
                if (tuned.options.firstOrNull()?.qn == qn) return tuned
            }
        }
        return null
    }

    /**
     * 让某视频的预取结果与在途任务全部失效。
     *
     * 播放失败(典型:直链签名过期/CDN 403)后点「重试」必须重新解析拿新直链,
     * 但 5 分钟 TTL 的预取缓存会原样返回同一批失效 URL,让"重试"变成死循环。
     */
    fun invalidate(repository: MediaRepositoryApi, videoId: String) {
        if (videoId.isBlank()) return
        val provider = repository.currentProvider
        synchronized(lock) {
            val cachedKeys = cache.keys.filter { it.provider == provider && it.videoId == videoId }
            cachedKeys.forEach { cache.remove(it) }
            val inflightKeys = inflight.keys.filter { it.provider == provider && it.videoId == videoId }
            inflightKeys.forEach { key -> inflight.remove(key)?.cancel() }
        }
    }

    /** 仅测试用:清空缓存与在途任务(JVM 测试进程内所有用例共享这个单例)。 */
    @androidx.annotation.VisibleForTesting
    internal fun clearForTest() {
        synchronized(lock) {
            inflight.values.forEach { it.cancel() }
            inflight.clear()
            cache.clear()
        }
    }

    /** 播放页解析成功后回填,后续切分P/重试可复用。 */
    fun put(repository: MediaRepositoryApi, videoId: String, qn: Int?, result: PlaybackResult) {
        put(keyOf(repository, videoId, qn), result)
    }

    private fun keyOf(repository: MediaRepositoryApi, videoId: String, qn: Int?) =
        Key(repository.currentProvider, videoId, qn)

    /** TTL 内命中返回结果并保留缓存项(过期项顺手清掉),供快速回看重复复用。 */
    private fun get(key: Key): PlaybackResult? {
        val entry = synchronized(lock) { cache[key] } ?: return null
        if (fresh(entry)) return entry.result
        synchronized(lock) { cache.remove(key) }
        return null
    }

    private fun put(key: Key, result: PlaybackResult) {
        synchronized(lock) {
            cache[key] = Entry(result, System.currentTimeMillis())
            while (cache.size > maxEntries) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    private fun fresh(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.fetchedAtMs <= TTL_MS
}
