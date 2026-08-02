package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.log.AppLog

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
) {
    // 当前选中的 provider(默认 bilibili)。首次加载 providers 后自动选第一个,
    // 之后用户手动切换优先;provider 状态在全局共享实例上,首页/搜索/播放页联动。
    @Volatile var currentProvider: String = "bilibili"
        private set
    private val providerInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun providers(): List<ProviderInfo> {
        refreshCredentials()
        val items = api.providers().items.orEmpty()
        if (items.isNotEmpty() && !providerInitialized.getAndSet(true)) {
            currentProvider = items.first().id
        }
        return items
    }

    /** 用户手动切换视频源。 */
    fun setProvider(provider: String) {
        if (provider == currentProvider) return
        currentProvider = provider
        AppLog.i("Repo", "switch provider to $provider")
    }

    // 从 server 拉取 provider 凭证并注入到对应的 API 客户端。
    // 失败时静默降级为无凭证模式(不影响基本播放)。
    suspend fun refreshCredentials() {
        try {
            val response = api.credentials()
            for (cred in response.credentials.orEmpty()) {
                if (cred.provider == "bilibili" && cred.fields?.isNotEmpty() == true) {
                    bilibiliApi.updateCredentials(cred.fields.orEmpty())
                }
            }
        } catch (e: Exception) {
            AppLog.w("Repo", "failed to fetch provider credentials, using no-credential mode", e)
        }
    }

    suspend fun categories(): List<ContentCategory> = api.categories(currentProvider).items.orEmpty()

    suspend fun categories(provider: String): List<ContentCategory> = api.categories(provider).items.orEmpty()

    // 拉取某个分类某一页的 approved 视频列表(server 只提供目录数据,无搜索接口)。
    suspend fun catalogPage(categoryId: String, page: Int = 1, pageSize: Int = 18): VideosResponse =
        api.categoryVideos(currentProvider, categoryId, page, pageSize)

    /**
     * 解析播放地址:按当前 provider 分发。bilibili 走 WBI 签名 + view + playurl
     * (bvid 支持 "bvid_pN" 多P分条格式);其他 provider 无本地解析器时给出明确错误。
     */
    suspend fun resolvePlayback(videoId: String, qn: Int? = null): PlaybackResult = when (currentProvider) {
        "bilibili" ->
            if (qn == null) bilibiliApi.resolvePlayback(videoId) else bilibiliApi.resolvePlayback(videoId, qn)
        else ->
            throw Exception("「${currentProvider}」视频源暂不支持在线播放")
    }
}
