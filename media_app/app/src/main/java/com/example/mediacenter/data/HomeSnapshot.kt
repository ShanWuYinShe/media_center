package com.example.mediacenter.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 首页首屏快照:上次成功加载的「当前分类 + 第一页」内容。
 *
 * 用途是 SWR(stale-while-revalidate):冷启动/回前台先用快照渲染,再后台刷新,
 * 避免 TV 盒子每次启动都先看 1-3s 骨架/转圈。快照是可丢弃数据,损坏/过期直接忽略。
 */
@Serializable
data class HomeSnapshot(
    val provider: String = "",
    val categories: List<ContentCategory> = emptyList(),
    val selectedTab: String = "",
    val items: List<VideoSummary> = emptyList(),
    val total: Int = 0,
    val savedAtEpochMs: Long = 0L,
)

/** 快照 JSON 编解码(纯逻辑,单测不依赖 Android)。 */
object HomeSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: HomeSnapshot): String = json.encodeToString(snapshot)

    fun decode(raw: String?): HomeSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<HomeSnapshot>(raw) }.getOrNull()
    }
}

/** 首页快照本地存储:filesDir 下单个 JSON,临时文件 + rename 保证不读到半写文件。 */
object HomeSnapshotStore {
    private const val FILE_NAME = "home-snapshot.json"
    // SWR 的 stale 阈值:超过后快照仍展示(秒出),但后台立即刷新保证最终一致。
    private const val STALE_AFTER_MS = 10 * 60 * 1000L
    // 硬上限:超过后快照直接丢弃(数据结构可能已随版本演进,陈旧到没有意义)。
    private const val MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000L

    /** 加载结果:snapshot 非 null 时 stale 表示是否已超 SWR 新鲜窗口。 */
    data class Loaded(val snapshot: HomeSnapshot, val stale: Boolean)

    fun load(context: Context): Loaded? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val snapshot = HomeSnapshotCodec.decode(runCatching { file.readText() }.getOrNull()) ?: return null
        if (snapshot.categories.isEmpty() || snapshot.items.isEmpty()) return null
        return evaluate(snapshot.savedAtEpochMs, System.currentTimeMillis(), snapshot)
    }

    /** 纯逻辑:按快照年龄判定加载结果(JVM 可测)。 */
    internal fun evaluate(savedAtEpochMs: Long, nowMs: Long, snapshot: HomeSnapshot): Loaded? {
        val age = nowMs - savedAtEpochMs
        if (age > MAX_AGE_MS || age < 0) return null
        return Loaded(snapshot, stale = age > STALE_AFTER_MS)
    }

    fun save(context: Context, snapshot: HomeSnapshot) {
        runCatching {
            val target = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(HomeSnapshotCodec.encode(snapshot))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
