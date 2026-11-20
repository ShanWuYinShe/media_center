package com.example.mediacenter.data

/**
 * ViewModel 依赖的仓库契约。
 *
 * UI 层只依赖这个接口而不是具体实现,单测可以注入内存 fake(不需要 Android
 * Context / OkHttp / SharedPreferences),也让"目录"与"播放解析"的边界显式化。
 */
interface MediaRepositoryApi {
    val currentProvider: String

    fun setProvider(provider: String)

    suspend fun providers(): List<ProviderInfo>

    suspend fun refreshCredentials()

    suspend fun categories(): List<ContentCategory>

    /**
     * 首页启动聚合数据(providers + 当前 provider 的分类)。
     * 默认实现退化为原有两次请求——单测 fake 不必实现;真实仓库覆盖为一次
     * bootstrap 请求,把冷启动的串行 RTT 从两次降到一次。
     */
    suspend fun bootstrap(): Pair<List<ProviderInfo>, List<ContentCategory>> =
        providers() to categories()

    suspend fun catalogPage(
        categoryId: String,
        page: Int = 1,
        pageSize: Int = 18,
        minDurationSeconds: Long? = null,
        maxDurationSeconds: Long? = null,
    ): VideosResponse

    suspend fun collectionFor(videoId: String): VideoCollectionResponse

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 30): SearchResponse

    suspend fun youtubeSearch(query: String): SearchResponse

    /**
     * B 站**全站**实时搜索(服务端代理 B 站官方搜索接口，结果不写 catalog)。
     * 与 [search] 的区别:search 只查已审核目录，这里能搜到全站视频。
     */
    suspend fun bilibiliSearch(query: String, page: Int = 1): SearchResponse

    suspend fun resolvePlayback(videoId: String, qn: Int? = null): PlaybackResult

    /**
     * Cloudflare 优选 IP 候选池(服务端下发，客户端自己测速排序)。
     * 默认返回 null:单测 fake 不必实现;拉取失败也不影响任何功能(退回内置池)。
     */
    suspend fun fetchPreferredIps(): List<String>? = null
}
