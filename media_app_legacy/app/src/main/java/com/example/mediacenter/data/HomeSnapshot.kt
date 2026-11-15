package com.example.mediacenter.data

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 首页首屏快照:上次成功加载的「当前分类 + 第一页」。
 *
 * 老盒子网络慢,冷启动先看 1-3s 转圈再出内容;快照让首帧直接是上次的列表,
 * 再后台静默刷新(SWR)。快照是可丢弃缓存:损坏/过期/provider 不符直接忽略。
 */
@Serializable
data class HomeSnapshot(
    val provider: String = "",
    val categories: List<ContentCategory> = emptyList(),
    val selectedCategoryId: String = "",
    val items: List<VideoSummary> = emptyList(),
    val page: Int = 1,
    val canLoadMore: Boolean = false,
    val savedAtEpochMs: Long = 0L,
)

/** 快照 JSON 编解码(纯逻辑,不依赖 Android)。 */
object HomeSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: HomeSnapshot): String = json.encodeToString(snapshot)

    fun decode(raw: String?): HomeSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<HomeSnapshot>(raw) }.getOrNull()
    }
}

/** 快照本地存储:filesDir 下单个 JSON,临时文件 + rename 避免读到半写文件。 */
object HomeSnapshotStore {
    private const val FILE_NAME = "home-snapshot.json"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun load(context: Context): HomeSnapshot? {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return null
            val snapshot = HomeSnapshotCodec.decode(file.readText()) ?: return null
            if (System.currentTimeMillis() - snapshot.savedAtEpochMs > MAX_AGE_MS) return null
            if (snapshot.categories.isEmpty() || snapshot.items.isEmpty()) return null
            snapshot
        }.getOrNull()
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
}
