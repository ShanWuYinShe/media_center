package com.example.mediacenter.data

import android.content.Context
import com.example.mediacenter.BuildConfig

/**
 * 全局共享的 MediaRepository:provider 选择(当前视频源)是所有页面共享的状态,
 * 首页/搜索/分类浏览/播放解析都必须看到同一个 currentProvider。
 *
 * provider 选择持久化到 SharedPreferences:由 Application 启动时调用 [init]
 * 注入 context,重启后恢复上次选择的视频源。
 */
object MediaRepositoryHolder {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val repository: MediaRepository by lazy {
        val prefs = appContext?.getSharedPreferences("media_prefs", Context.MODE_PRIVATE)
        // 独立缓存目录:OkHttp Cache 要求独占目录,不能与其他组件共享。
        val apiCacheDir = appContext?.cacheDir?.resolve("api-cache")
        MediaRepository(api = MediaApi(BuildConfig.MEDIA_SERVER_URL, cacheDir = apiCacheDir), prefs = prefs)
    }

    // App 更新检查/下载与崩溃上报等独立于 catalog 仓库的轻量客户端:全局共享
    // 一个实例,复用同一 OkHttpClient 的连接池与线程池——此前首页/设置页/崩溃
    // 上报各自 new MediaApi(连接池不复用,崩溃上报还是默认 10s 超时)。
    val updateApi: MediaApi by lazy { MediaApi(BuildConfig.MEDIA_SERVER_URL) }
}
