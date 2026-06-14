package com.example.mediacenter.data

import java.io.IOException
import com.example.mediacenter.log.AppLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

class MediaApi(
    private val baseUrl: String,
    // connectTimeout 15s 与 readTimeout 对齐：Android TV 模拟器冷启动 + emulator
    // 网络栈刚就绪时,三次握手偶尔超过 5s,过紧的超时会直接抛 "Failed to connect"。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val apiRoot = HttpUrl.parse(baseUrl)
        ?: throw IllegalArgumentException("MEDIA_SERVER_URL must be a valid URL")
    private val baseUrlIncludesApiPrefix = apiRoot.pathSegments().any { it.isNotEmpty() }

    suspend fun search(query: String, page: Int = 1, pageSize: Int = 18): SearchResponse = get(
        pathSegments = listOf("search"),
        query = mapOf(
            "q" to query,
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    suspend fun searchCategory(
        categoryId: String,
        page: Int = 1,
        pageSize: Int = 18,
    ): SearchResponse = get(
        pathSegments = listOf("categories", categoryId, "videos"),
        query = mapOf(
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    suspend fun categories(): CategoryResponse = get(pathSegments = listOf("categories"))

    suspend fun details(id: String): VideoDetails = get(
        pathSegments = listOf("videos", id),
    )

    suspend fun playback(id: String, preferProxy: Boolean = false): PlaybackOptionsResponse = get(
        pathSegments = listOf("videos", id, "playback"),
        query = if (preferProxy) mapOf("delivery" to DeliveryMode.PROXY) else emptyMap(),
    )

    suspend fun appUpdate(currentVersionCode: Int): AppUpdateInfo? {
        val body = getOptionalBody(
            pathSegments = listOf("app", "update"),
            query = mapOf("versionCode" to currentVersionCode.toString()),
        ) ?: return null
        return withContext(Dispatchers.Default) {
            json.decodeFromString<AppUpdateInfo>(body)
        }
    }

    fun resolveApiUrl(pathSegments: List<String>): String {
        val builder = apiRoot.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        return builder.build().toString()
    }

    private suspend inline fun <reified T> get(
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): T {
        val builder = apiRoot.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build()).get().build()
        val label = "/" + pathSegments.joinToString("/")
        val startedAt = System.currentTimeMillis()

        // 重试覆盖两类瞬时故障：
        //   1. IOException —— 冷启动握手抖动、连接重置等网络层错误。
        //   2. 瞬时 5xx(502/503/504 等)—— 服务端/网关在拥塞时返回的临时错误。
        //      与服务端"拥塞应降级为变慢而非报错"的原则、以及缩略图加载器的重试口径
        //      保持一致;业务类 4xx 与反序列化错误不重试,立即上抛。
        var attempt = 0
        var lastError: Throwable? = null
        AppLog.d("Api", "GET $label query=${query.keys}")
        while (true) {
            try {
                val body = execute(request)
                val result = withContext(Dispatchers.Default) {
                    json.decodeFromString<T>(body)
                }
                AppLog.i(
                    "Api",
                    "GET $label ok in ${System.currentTimeMillis() - startedAt}ms" +
                        if (attempt > 0) " (after $attempt retr${if (attempt == 1) "y" else "ies"})" else "",
                )
                return result
            } catch (error: IOException) {
                lastError = error
                if (attempt >= MAX_RETRIES) break
                AppLog.w("Api", "GET $label IO error, retry ${attempt + 1}/$MAX_RETRIES: ${error.message}")
                // 退避 500ms / 1500ms,总附加等待 ≤ 2s,不会让用户感觉卡顿。
                kotlinx.coroutines.delay(RETRY_BACKOFF_MS[attempt])
                attempt++
            } catch (error: ApiException) {
                if (!error.isTransient || attempt >= MAX_RETRIES) {
                    AppLog.w("Api", "GET $label http ${error.statusCode} code=${error.errorCode}")
                    throw error
                }
                lastError = error
                AppLog.w("Api", "GET $label transient ${error.statusCode}, retry ${attempt + 1}/$MAX_RETRIES")
                kotlinx.coroutines.delay(RETRY_BACKOFF_MS[attempt])
                attempt++
            }
        }
        // 走到这里只可能是网络层 IOException 重试耗尽(瞬时 5xx 在上方已直接抛出),
        // 统一包装成面向用户的友好文案,同时保留原始异常便于排查。
        AppLog.w("Api", "GET $label failed after ${attempt} retries in ${System.currentTimeMillis() - startedAt}ms", lastError)
        throw IOException(NETWORK_FAILURE_MESSAGE, lastError)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.requiredBody()
                        if (!it.isSuccessful) {
                            throw apiException(it.code(), body)
                        }
                        if (continuation.isActive) {
                            continuation.resume(body)
                        }
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private suspend fun getOptionalBody(
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): String? {
        val builder = apiRoot.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build()).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code() == 204) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            val body = it.requiredBody()
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
    }

    private fun Response.requiredBody(): String {
        return body()?.string() ?: throw IOException("响应体为空")
    }

    private fun apiException(statusCode: Int, body: String): ApiException {
        val apiError = runCatching {
            json.decodeFromString<ApiErrorEnvelope>(body).error
        }.getOrNull()
        return ApiException(
            statusCode = statusCode,
            errorCode = apiError?.code,
            errorMessage = apiError?.message ?: statusCode.toFriendlyHttpMessage(),
        )
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val NETWORK_FAILURE_MESSAGE = "网络连接失败，请检查网络后重试"
        val RETRY_BACKOFF_MS = longArrayOf(500L, 1500L)
    }
}

class ApiException(
    val statusCode: Int,
    val errorCode: String?,
    errorMessage: String,
) : Exception(errorMessage) {
    // 口径统一见 [isTransientHttpStatusCode]：408/425/429 与 5xx 视为瞬时,可重试;
    // 其余 4xx 为业务错误,不重试。
    val isTransient: Boolean
        get() = statusCode.isTransientHttpStatusCode()
}

internal val Throwable.isNoPlayableStream: Boolean
    get() = this is ApiException && errorCode == "NO_PLAYABLE_STREAM"

@Serializable
private data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
)

@Serializable
private data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
)
