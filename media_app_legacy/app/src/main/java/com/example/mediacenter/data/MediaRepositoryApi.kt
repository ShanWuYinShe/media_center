package com.example.mediacenter.data

/** ViewModel 依赖的仓库契约,便于隔离 Android 实现与后续单测。 */
interface MediaRepositoryApi {
    val currentProvider: String

    /** HTTPS 握手失败后是否已降级到明文 HTTP(诊断用)。 */
    val httpFallbackActive: Boolean

    fun selectProvider(provider: String)

    suspend fun providers(): List<ProviderInfo>

    suspend fun refreshCredentials()

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
