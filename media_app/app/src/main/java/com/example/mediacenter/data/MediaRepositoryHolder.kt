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
        MediaRepository(api = MediaApi(BuildConfig.MEDIA_SERVER_URL), prefs = prefs)
    }
}
