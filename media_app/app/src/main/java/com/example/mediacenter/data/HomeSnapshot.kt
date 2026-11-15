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
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun load(context: Context): HomeSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val snapshot = HomeSnapshotCodec.decode(runCatching { file.readText() }.getOrNull()) ?: return null
        if (System.currentTimeMillis() - snapshot.savedAtEpochMs > MAX_AGE_MS) return null
        if (snapshot.categories.isEmpty() || snapshot.items.isEmpty()) return null
        return snapshot
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
