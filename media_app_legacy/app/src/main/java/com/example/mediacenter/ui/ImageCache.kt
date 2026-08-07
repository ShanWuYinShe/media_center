package com.example.mediacenter.ui

import android.graphics.Bitmap
import android.util.LruCache
import com.example.mediacenter.data.isTransientHttpStatusCode
import com.example.mediacenter.net.AppHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient

object ImageMemoryCache {
    // 按应用可用堆的 1/8 估算缓存上限(Android 推荐做法),再夹到 8~24MB。
    // 低堆的 API19 电视盒子会自动收缩,避免缩略图缓存撑爆内存导致 OOM。
    private val maxSizeKb: Int = ((Runtime.getRuntime().maxMemory() / 1024) / 8)
        .coerceIn(8L * 1024, 24L * 1024)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }
}

class ThumbnailHttpException(val code: Int) : IOException("Thumbnail HTTP $code") {
    val isTransient: Boolean get() = code.isTransientHttpStatusCode()
}

// 缩略图并发限流:最多 4 个同时下载,防止滚动长列表时打满网络/内存。
val ThumbnailFetchSemaphore = Semaphore(4)

// 缩略图客户端:带磁盘缓存 + trust-all TLS。8s 连接超时,15s 读超时,跟随重定向
// (上游图床可能 302 到 CDN)。注意:newBuilder() 会复制 Cache——参见
// OkHttpClient.newBuilder() 文档(新 client 共享原 cache)。
val ThumbnailHttpClient: OkHttpClient = AppHttpClient.cachedShared.newBuilder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()
