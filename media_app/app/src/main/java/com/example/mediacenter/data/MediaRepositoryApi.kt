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

    suspend fun resolvePlayback(videoId: String, qn: Int? = null): PlaybackResult
}
