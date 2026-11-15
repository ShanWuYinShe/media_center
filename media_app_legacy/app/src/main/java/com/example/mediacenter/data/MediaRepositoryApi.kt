package com.example.mediacenter.data

/** ViewModel 依赖的仓库契约,便于隔离 Android 实现与后续单测。 */
interface MediaRepositoryApi {
    val currentProvider: String

    /** HTTPS 握手失败后是否已降级到明文 HTTP(诊断用)。 */
    val httpFallbackActive: Boolean

    fun selectProvider(provider: String)

    suspend fun providers(): List<ProviderInfo>

    /**
     * 首页启动聚合数据(providers + 当前 provider 分类)。
     * 默认实现退化为原有两次请求;真实仓库覆盖为一次 bootstrap 请求,
     * 老盒子冷启动少一次串行 RTT。
     */
    suspend fun bootstrap(): Pair<List<ProviderInfo>, List<ContentCategory>> =
        providers() to categories()

    suspend fun refreshCredentials()

    /** 首页首屏快照(可丢弃缓存);默认实现不持久化,便于 fake/无 Context 场景。 */
    fun loadHomeSnapshot(): HomeSnapshot? = null

    fun saveHomeSnapshot(snapshot: HomeSnapshot) = Unit

    suspend fun categories(): List<ContentCategory>

    suspend fun search(
        query: String,
        categoryId: String?,
        page: Int = 1,
        pageSize: Int = 18,
    ): SearchResponse

    fun details(video: VideoSummary): VideoDetails

    suspend fun playbackOptions(video: VideoDetails): PlaybackResult

    suspend fun playbackOptions(video: VideoDetails, qn: Int): PlaybackResult

    suspend fun resolvePagePlayback(bvid: String, pageIndex: Int): List<PlaybackOption>
}
