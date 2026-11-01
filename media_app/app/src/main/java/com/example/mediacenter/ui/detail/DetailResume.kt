package com.example.mediacenter.ui.detail

import com.example.mediacenter.data.parsePageId

/**
 * 详情页续播应读取的分P下标(0 起始)。
 *
 * 目录里的多P条目 id 可能自带 `_pN` 后缀;播放器按“videoId + 页码”组合存储键。
 * 详情页必须用同一组合键读取,否则多P续播提示永远为空。
 *
 * `_pN` 是 bilibili catalog 的约定;YouTube 视频 ID 字符集也含 `_`,必须按
 * provider 限定解析,不能对任意 id 套用。
 */
internal fun resumePageIndex(videoId: String, provider: String, lastWatchedPage: Int): Int {
    val pageFromId = if (provider == "bilibili") parsePageId(videoId).second else 0
    return maxOf(lastWatchedPage, pageFromId)
}
