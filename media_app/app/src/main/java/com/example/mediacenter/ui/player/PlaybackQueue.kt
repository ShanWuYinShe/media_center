package com.example.mediacenter.ui.player

import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.VideoCollectionResponse
import com.example.mediacenter.data.VideoSummary

/**
 * 播放器内的合集队列(已由服务端过滤为 approved 剧集)。index 为当前视频
 * 在 items 中的位置;-1 表示当前视频不在队列里(不得据此连播)。
 */
data class PlaybackCollection(
    val id: String,
    val title: String,
    val items: List<VideoSummary>,
    val currentIndex: Int,
) {
    /** 合集列表中的下一个已审核剧集;没有则 null。 */
    fun next(): VideoSummary? {
        if (currentIndex < 0 || currentIndex >= items.size) return null
        return items.getOrNull(currentIndex + 1)
    }
}

/** 片尾自动连播的目标:多P优先,其次合集下一集。 */
sealed interface PlaybackNext {
    data class Page(val index: Int) : PlaybackNext

    data class Episode(val video: VideoSummary) : PlaybackNext
}

/** 计算片尾的下一步;没有下一页/下一集返回 null。 */
fun nextPlayback(result: PlaybackResult?, collection: PlaybackCollection?): PlaybackNext? {
    val page = result?.pages?.let(::nextPageIndex)
    if (page != null) return PlaybackNext.Page(page)
    val episode = collection?.next() ?: return null
    return PlaybackNext.Episode(episode)
}

/** 多P视频的下一页下标;非多P/已是最后一页返回 null。 */
fun nextPageIndex(pages: PlaybackPages): Int? {
    val next = pages.currentPage + 1
    return next.takeIf { pages.currentPage >= 0 && it in 0 until pages.totalPages }
}

/** 把接口响应转成播放器队列;无合集/空队列返回 null,播放器静默退化。 */
fun playbackCollectionFrom(response: VideoCollectionResponse): PlaybackCollection? {
    val info = response.collection ?: return null
    val items = response.items.orEmpty()
    if (items.isEmpty()) return null
    val index = response.currentIndex.takeIf { it in items.indices } ?: -1
    return PlaybackCollection(id = info.id, title = info.title, items = items, currentIndex = index)
}
