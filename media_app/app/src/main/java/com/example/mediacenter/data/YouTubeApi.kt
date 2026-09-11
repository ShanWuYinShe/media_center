package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.net.AppHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * YouTube provider 的 App 端 API 客户端:搜索与播放解析都走 media_server 的
 * youtube 实时端点(server 侧调 Data API / 代理 vod_extractor),App 不直接
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
        // 解析代理会等 vod_extractor 跑完 yt-dlp(秒级),15s 的目录超时不够。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
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
        return execute(request, "youtube search")
    }

    /** 解析播放地址(直链多档 + 清晰度列表)。服务端解析失败时抛 IOException/ApiException。 */
    suspend fun resolvePlayback(videoId: String): PlaybackResult {
        val request = mediaApi.buildRequest(
            pathSegments = listOf("providers", "youtube", "resolve"),
            query = mapOf("id" to videoId),
            headers = appTokenHeaders(),
        )
        val response = execute(request, "youtube resolve") { body ->
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

    private suspend inline fun <reified T> execute(request: Request, label: String): T =
        execute(request, label) { body -> json.decodeFromString<T>(body) }

    private suspend fun <T> execute(request: Request, label: String, decode: (String) -> T): T {
        val body = executeBody(request, label)
        return withContext(Dispatchers.Default) { decode(body) }
    }

    private suspend fun executeBody(request: Request, label: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    try {
                        response.use {
                            val body = it.body.string()
                            if (!it.isSuccessful) {
                                throw apiException(it.code, body)
                            }
                            if (continuation.isActive) continuation.resume(body)
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
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
