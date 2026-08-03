package com.example.mediacenter.net

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.player.BILI_CDN_HEADERS
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 进程级 [ImageLoader] 构造器:为缩略图请求注入 B 站 CDN 必需的 Referer/UA。
 *
 * 为什么需要:catalog 的缩略图是上游原始地址(B 站 CDN,如 i0/i1/i2.hdslb.com),
 * 直连时必须带 `Referer: https://www.bilibili.com`,否则 CDN 返回 403、卡片/详情页
 * 全部显示空封面(见 media_server API.md「App 直连带 Referer」约定)。Coil 默认用裸
 * OkHttp,不带任何头,因此这里集中注入。只对 B 站图床域名加头,其它请求(如自建资源)
 * 不受影响。
 *
 * 用法:`Application` 实现 `coil.ImageLoaderFactory`,`newImageLoader()` 委托给 [build]。
 * Coil 在首个 `AsyncImage` 请求前会通过 `SingletonImageLoader.get(context)` 拿到本工厂
 * 产出的实例(见 [com.example.mediacenter.MediaCenterApp])。
 */
object BilibiliImageLoaderFactory {
    private const val CACHE_MAX_SIZE_BYTES = 80L * 1024 * 1024 // 80MB 磁盘缓存

    /** 由 Application 的 newImageLoader() 调用,传入 Application context。 */
    fun build(context: Context): ImageLoader {
        val okHttp: OkHttpClient = AppHttpClient.newBuilder()
            .addInterceptor(BilibiliThumbnailHeaderInterceptor())
            .build()
        return ImageLoader.Builder(context)
            .okHttpClient { okHttp }
            .crossfade(true)
            .respectCacheHeaders(false) // B 站图床无明确 cache-control,以本地策略为准
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.20).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            .build()
            .also { AppLog.i("Image", "Coil ImageLoader configured with bilibili Referer interceptor") }
    }
}

/**
 * 仅对 B 站图床域名(`*.hdslb.com`、`*.bilibili.com`)注入 Referer/Origin/UA,
 * 避免影响其它图片源(如自建静态资源)。请求已显式带同名头时不覆盖。
 */
private class BilibiliThumbnailHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val host = request.url.host
        if (host.endsWith("hdslb.com", ignoreCase = true) || host.endsWith("bilibili.com", ignoreCase = true)) {
            val builder = request.newBuilder()
            BILI_CDN_HEADERS.forEach { (name, value) ->
                if (request.header(name) == null) builder.header(name, value)
            }
            return chain.proceed(builder.build())
        }
        return chain.proceed(request)
    }
}
