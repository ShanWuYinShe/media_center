package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig

/**
 * 全局共享的 MediaRepository:provider 选择(当前视频源)是所有页面共享的状态,
 * 首页/搜索/分类浏览/播放解析都必须看到同一个 currentProvider。
 */
object MediaRepositoryHolder {
    val repository: MediaRepository by lazy {
        MediaRepository(api = MediaApi(BuildConfig.MEDIA_SERVER_URL))
    }
}
