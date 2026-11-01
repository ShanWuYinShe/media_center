package com.example.mediacenter.data

import java.io.IOException
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.net.AppHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube provider 的 App 端 API 客户端:搜索与播放解析都走 media_server 的
 * youtube 实时端点(server 侧经 vod_extractor 代理 yt-dlp),App 不直接
 * 访问 YouTube——解析能力集中在服务端,youtube 风控变化只需服务端更新
 * yt-dlp,App 无需发版。
 *
 * 与 BilibiliApi 的差异:请求目标是自家 media_server 而非上游,故复用
 * [MediaApi.buildRequest] 保证 api 前缀等 URL 逻辑单源;鉴权用 X-App-Token
 * (与凭证接口同一令牌)。
 */
class YouTubeApi(
    private val mediaApi: MediaApi,
    private val client: OkHttpClient = AppHttpClient.newBuilder()
        // 解析代理会等 vod_extractor 跑完 yt-dlp + Deno 挑战求解 + PO Token；
        // 冷启动实测 25–30s，服务端超时 70s，这里留到 90s。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    /** YouTube 实时搜索(当前 provider 必须为 youtube)。 */
    suspend fun search(query: String): SearchResponse {
        val request = mediaApi.buildRequest(
            pathSegments = listOf("providers", "youtube", "search"),
            query = mapOf("q" to query),
            headers = appTokenHeaders(),
        )
        return execute(request)
    }

    /** 解析播放地址(直链多档 + 清晰度列表)。服务端解析失败时抛 IOException/ApiException。 */
    suspend fun resolvePlayback(videoId: String): PlaybackResult {
        val request = mediaApi.buildRequest(
            pathSegments = listOf("providers", "youtube", "resolve"),
            query = mapOf("id" to videoId),
            headers = appTokenHeaders(),
        )
        val response = execute(request) { body ->
            json.decodeFromString<YouTubeResolveResponse>(body)
        }
        return response.toPlaybackResult()
    }

    private fun appTokenHeaders(): Map<String, String> =
        if (BuildConfig.APP_API_TOKEN.isNotEmpty()) {
            mapOf("X-App-Token" to BuildConfig.APP_API_TOKEN)
        } else {
            emptyMap()
        }

    private suspend inline fun <reified T> execute(request: Request): T =
        execute(request) { body -> json.decodeFromString<T>(body) }

    private suspend fun <T> execute(request: Request, decode: (String) -> T): T {
        val body = executeBody(request)
        return withContext(Dispatchers.Default) { decode(body) }
    }

    private suspend fun executeBody(request: Request): String = withContext(Dispatchers.IO) {
        val response = client.newCall(request).await()
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                throw apiException(it.code, body)
            }
            body
        }
    }

    private fun apiException(statusCode: Int, body: String): ApiException {
        val parser = Json { ignoreUnknownKeys = true }
        val apiError = runCatching {
            parser.decodeFromString<ApiErrorEnvelope>(body).error
        }.getOrNull()
        return ApiException(
            statusCode = statusCode,
            errorCode = apiError?.code,
            message = apiError?.message ?: statusCode.toFriendlyHttpMessage(),
        )
    }
}

/** /api/providers/youtube/resolve 的响应(server 端 PlaybackResultResponse)。 */
@Serializable
internal data class YouTubeResolveResponse(
    val options: List<PlaybackOption> = emptyList(),
    val availableQualities: List<QualityOption> = emptyList(),
    val durationSeconds: Long = 0,
) {
    fun toPlaybackResult(): PlaybackResult {
        if (options.isEmpty()) {
            throw IOException("YouTube 解析结果为空")
        }
        // YouTube 无多P概念,pages 保持默认单页。
        return PlaybackResult(options = options, availableQualities = availableQualities)
    }
}
