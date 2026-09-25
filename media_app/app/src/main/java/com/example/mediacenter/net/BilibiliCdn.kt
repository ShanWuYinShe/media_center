package com.example.mediacenter.net

/**
 * B 站 CDN 反爬必需的请求头:PC 端 User-Agent + Referer/Origin 指向 bilibili.com。
 * 不带这些头,CDN 直接返回 403,播放流/缩略图无法加载。
 *
 * 单一来源放在 net 层:data 层(BilibiliApi 解析 playurl/注入直链头)、player 层
 * (PlaybackService 的 HttpDataSource)与图片加载(BilibiliImageLoaderFactory)共同引用,
 * 修改时只改这里。
 */
internal val BILI_CDN_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer" to "https://www.bilibili.com",
    "Origin" to "https://www.bilibili.com",
)
