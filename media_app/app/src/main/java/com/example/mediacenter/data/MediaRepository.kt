package com.example.mediacenter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.log.AppLog

class MediaRepository(
    private val api: MediaApi,
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
    private val youtubeApi: YouTubeApi = YouTubeApi(api),
    prefs: SharedPreferences? = null,
) {
    // 当前选中的 provider(默认 bilibili)。首次加载 providers 后自动选第一个,
    // 之后用户手动切换优先;provider 状态在全局共享实例上,首页/搜索/播放页联动。
    // 选择持久化到 SharedPreferences,App 重启后恢复,不回到默认源。
    @Volatile var currentProvider: String = prefs?.getString(KEY_CURRENT_PROVIDER, null) ?: "bilibili"
        private set
    private val providerInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private val providerPrefs = prefs
    // 凭证刷新 TTL:凭证变化低频,首页每次加载都拉一次纯属浪费一个请求。
    private var lastCredentialsRefreshMs = 0L

    suspend fun providers(): List<ProviderInfo> {
        refreshCredentials()
        val items = api.providers().items.orEmpty()
        if (items.isNotEmpty() && !providerInitialized.getAndSet(true)) {
            // 仅当没有持久化选择、或持久化的 provider 已从列表下线时才自动选
            // 第一个:currentProvider 初值已从 prefs 恢复,这里再无条件覆盖会让
            // 用户每次重启都被拉回默认源(与注释"不回到默认源"相悖)。
            val restored = providerPrefs?.getString(KEY_CURRENT_PROVIDER, null)
            if (restored.isNullOrEmpty() || items.none { it.id == restored }) {
                currentProvider = items.first().id
                persistProvider()
            }
        }
        return items
    }

    /** 用户手动切换视频源。 */
    fun setProvider(provider: String) {
        if (provider == currentProvider) return
        currentProvider = provider
        persistProvider()
        AppLog.i("Repo", "switch provider to $provider")
    }

    private fun persistProvider() {
        providerPrefs?.edit { putString(KEY_CURRENT_PROVIDER, currentProvider) }
    }

    companion object {
        private const val KEY_CURRENT_PROVIDER = "current_provider"
        // 凭证刷新间隔:凭证只在管理端变更,5 分钟 TTL 足够;失败不刷新 TTL。
        private const val CREDENTIALS_REFRESH_TTL_MS = 5L * 60 * 1000
    }

    // 从 server 拉取 provider 凭证并注入到对应的 API 客户端。
    // 失败时静默降级为无凭证模式(不影响基本播放);TTL 内不重复拉取。
    suspend fun refreshCredentials() {
        val now = System.currentTimeMillis()
        if (now - lastCredentialsRefreshMs < CREDENTIALS_REFRESH_TTL_MS) return
        try {
            val response = api.credentials()
            for (cred in response.credentials.orEmpty()) {
                if (cred.provider == "bilibili" && cred.fields?.isNotEmpty() == true) {
                    bilibiliApi.updateCredentials(cred.fields.orEmpty())
                }
            }
            // 只有成功才刷新 TTL:失败时下次调用会重试,不会卡在过期缓存上。
            lastCredentialsRefreshMs = now
        } catch (e: Exception) {
            AppLog.w("Repo", "failed to fetch provider credentials, using no-credential mode", e)
        }
    }

    suspend fun categories(): List<ContentCategory> = api.categories(currentProvider).items.orEmpty()

    suspend fun categories(provider: String): List<ContentCategory> = api.categories(provider).items.orEmpty()

    // 拉取某个分类某一页的 approved 视频列表(server 只提供目录数据,无搜索接口)。
    suspend fun catalogPage(categoryId: String, page: Int = 1, pageSize: Int = 18): VideosResponse =
        api.categoryVideos(currentProvider, categoryId, page, pageSize)

    /** 服务端搜索(当前 provider 内,approved 目录)。失败时由调用方回退本地过滤。 */
    suspend fun search(query: String): List<VideoSummary> =
        api.search(query, currentProvider).items.orEmpty()

    /**
     * YouTube 实时搜索:不走 catalog,直接调 server 的 YouTube 通道(ytsearch 实时搜索)。
     * 与 [search] 分开是为了让搜索层按 provider 显式选择通道,失败时仍可回退
     * 本地 catalog 过滤(能搜到已入库的少量内容)。
     */
    suspend fun youtubeSearch(query: String): SearchResponse = youtubeApi.search(query)

    /**
     * 解析播放地址:按当前 provider 分发。bilibili 走 WBI 签名 + view + playurl
     * (bvid 支持 "bvid_pN" 多P分条格式);youtube 交给 server 的解析代理
     * (一次返回全部清晰度档,切档由播放器本地换源,qn 参数被忽略)。
     * 其他 provider 无本地解析器时给出明确错误。
     */
    suspend fun resolvePlayback(videoId: String, qn: Int? = null): PlaybackResult = when (currentProvider) {
        "bilibili" ->
            if (qn == null) bilibiliApi.resolvePlayback(videoId) else bilibiliApi.resolvePlayback(videoId, qn)
        "youtube" -> youtubeApi.resolvePlayback(videoId)
        else ->
            throw Exception("「${currentProvider}」视频源暂不支持在线播放")
    }
}
