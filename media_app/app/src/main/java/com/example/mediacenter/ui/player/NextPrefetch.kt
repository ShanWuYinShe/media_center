package com.example.mediacenter.ui.player

import com.example.mediacenter.data.parsePageId

/**
 * 计算"下一集"播放地址预取的 resolve 目标 id(纯逻辑,可 JVM 单测)。
 *
 * 多P下一页按 bilibili catalog 约定拼 `<bvid>_p<N>`(N 从 1 起);
 * 合集下一集直接用其 nativeId。无下一集返回 null(不预取)。
 */
internal fun nextPrefetchTargetId(
    currentVideoId: String,
    provider: String,
    next: PlaybackNext?,
): String? = when (next) {
    null -> null
    is PlaybackNext.Page -> {
        if (provider == "bilibili") {
            val (bvid, _) = parsePageId(currentVideoId)
            "${bvid}_p${next.index + 1}"
        } else {
            currentVideoId
        }
    }
    is PlaybackNext.Episode -> next.video.id
}

/**
 * 下一集预取的清晰度 key:
 * - 多P切页(selectPage)按当前档位传 currentQn,预取才能按 qn 精确命中;
 * - 合集切集(switchEpisode)会先把 currentQn 置 null,预取也必须用 null,
 *   否则写入 key 与切集查询 key 不一致,预取永远命中不了(还要白跑一次解析)。
 */
internal fun nextPrefetchQn(next: PlaybackNext?, currentQn: Int?): Int? =
    if (next is PlaybackNext.Episode) null else currentQn
