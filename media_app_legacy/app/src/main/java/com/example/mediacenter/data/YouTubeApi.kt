package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * YouTube provider 的 App 端 API 客户端(legacy 变体):搜索与播放解析都走
 * media_server 的 youtube 实时端点(server 侧调 Data API / 代理 vod_extractor)。
 * 解析能力集中在服务端,YouTube 风控变化只需服务端更新 yt-dlp,老设备无需发版。
 *
 * URL 由 [MediaApi.resolveApiUrl] 派生(与目录接口同源,自动跟随 HTTP 降级);
 * 鉴权用 X-App-Token(与凭证接口同一令牌)。
 */
class YouTubeApi(
    private val mediaApi: MediaApi,
    private val client: OkHttpClient = AppHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 解析代理会等 vod_extractor 跑完 yt-dlp(秒级),15s 的目录超时不够。
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    /** YouTube 实时搜索(当前 provider 必须为 youtube)。 */
    suspend fun search(query: String): SearchResponse {
        val url = buildUrl(listOf("providers", "youtube", "search")) {
            it.addQueryParameter("q", query)
        }
        return execute(url, "youtube search")
    }

    /** 解析播放地址(直链多档 + 清晰度列表)。 */
    suspend fun resolvePlayback(videoId: String): PlaybackResult {
        val url = buildUrl(listOf("providers", "youtube", "resolve")) {
            it.addQueryParameter("id", videoId)
        }
        val response = execute(url, "youtube resolve") { body ->
            json.decodeFromString<LegacyYouTubeResolveResponse>(body)
        }
        return response.toPlaybackResult()
    }

    private fun appTokenHeaders(): Map<String, String> =
        if (BuildConfig.APP_API_TOKEN.isNotEmpty()) {
            mapOf("X-App-Token" to BuildConfig.APP_API_TOKEN)
        } else {
            emptyMap()
        }

    private fun buildUrl(pathSegments: List<String>, query: (HttpUrl.Builder) -> Unit): String {
        val root = HttpUrl.parse(mediaApi.resolveApiUrl(pathSegments))
            ?: throw IOException("invalid url: ${mediaApi.baseUrl}")
        val builder = root.newBuilder()
        query(builder)
        return builder.build().toString()
    }    private suspend inline fun <reified T> execute(url: String, label: String): T =
        execute(url, label) { body -> json.decodeFromString<T>(body) }

    private suspend fun <T> execute(url: String, label: String, decode: (String) -> T): T {
        val body = executeBody(url, label)
        return withContext(Dispatchers.Default) { decode(body) }
    }

    private suspend fun executeBody(url: String, label: String): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .apply {
                    appTokenHeaders().forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val body = it.body()?.string() ?: throw IOException("响应体为空")
                            if (!it.isSuccessful) {
                                throw apiException(it.code(), body)
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
        val parser = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        // MediaApi 的 error envelope 是 private,这里本地定义同形结构。
        val apiError = runCatching {
            parser.decodeFromString<YouTubeErrorEnvelope>(body).error
        }.getOrNull()
        AppLog.w("YouTube", "http $statusCode code=${apiError?.code}")
        return ApiException(
            statusCode = statusCode,
            errorCode = apiError?.code,
            errorMessage = apiError?.message ?: statusCode.toFriendlyHttpMessage(),
        )
    }
}

@Serializable
private data class YouTubeErrorEnvelope(
    val error: YouTubeErrorBody? = null,
)

@Serializable
private data class YouTubeErrorBody(
    val code: String? = null,
    val message: String? = null,
)

/** /api/providers/youtube/resolve 的响应(server 端 PlaybackResultResponse)。 */
@Serializable
internal data class LegacyYouTubeResolveResponse(
    val options: List<PlaybackOption> = emptyList(),
    val availableQualities: List<PlaybackQuality> = emptyList(),
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
