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
