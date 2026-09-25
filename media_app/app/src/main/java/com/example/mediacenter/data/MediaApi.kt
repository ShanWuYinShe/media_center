package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.Duration

/** /health 响应:status 固定 "ok",version 为服务端构建版本(仓库 git describe)。 */
@kotlinx.serialization.Serializable
data class ServerHealth(val status: String? = null, val version: String? = null)

class MediaApi(
    val baseUrl: String,
    cacheDir: File? = null,
    private val client: OkHttpClient = AppHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(15))
        .apply {
            // 目录接口(/providers、/categories)低频变更,服务端已带 60s Cache-Control;
            // 配上磁盘缓存后,App 启动/回首页的重复拉取走缓存,省流量更跟手。只缓存 GET,
            // 崩溃上报(POST)不受影响;视频列表分页 query 不同各自独立缓存。
            if (cacheDir != null) cache(Cache(cacheDir, API_CACHE_MAX_BYTES))
        }
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    private val apiRoot = baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("MEDIA_SERVER_URL must be a valid URL")
    private val baseUrlIncludesApiPrefix = apiRoot.pathSegments.any { it.isNotEmpty() }

    suspend fun providers(): ProvidersResponse = get(listOf("providers"))

    // 凭证接口需要 X-App-Token(与服务端 MEDIA_SERVER_APP_API_TOKEN 一致):
    // 凭证是共享账号会话 cookie,服务端只向持令牌的客户端下发。
    suspend fun credentials(): CredentialsResponse =
        get(listOf("providers", "credentials"), headers = appTokenHeaders())

    suspend fun categories(provider: String): CategoriesResponse =
        get(listOf("providers", provider, "categories"))

    suspend fun categoryVideos(
        provider: String,
        categoryId: String,
        page: Int = 1,
        pageSize: Int = 18,
    ): VideosResponse = get(
        pathSegments = listOf("providers", provider, "categories", categoryId, "videos"),
        query = mapOf(
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    /** 服务端搜索(approved 目录,按 provider 过滤,跨分类去重排序)。 */
    suspend fun search(query: String, provider: String): SearchResponse = get(
        pathSegments = listOf("search"),
        query = mapOf(
            "q" to query,
            "provider" to provider,
        ),
    )

    /**
     * 服务端版本(设置页展示用)。/health 为公开端点,无需令牌;
     * 失败返回 null(展示场景快速失败,不走重试放大延迟)。
     */
    suspend fun serverVersion(): String? = try {
        get<ServerHealth>(pathSegments = listOf("health")).version
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 协程取消不当普通失败处理
        throw e
    } catch (error: Exception) {
        AppLog.w("Api", "server version: ${error.message}")
        null
    }

    suspend fun appUpdate(currentVersionCode: Int): AppUpdateInfo? {
        val body = getOptionalBody(
            pathSegments = listOf("app", "update"),
            query = mapOf(
                "versionCode" to currentVersionCode.toString(),
                "variant" to BuildConfig.APP_VARIANT,
            ),
        ) ?: return null
        return withContext(Dispatchers.Default) {
            json.decodeFromString<AppUpdateInfo>(body)
        }
    }

    /** 崩溃上报:发一次,无论成败都不重试(避免启动时卡网络)。成功返回 true。 */
    suspend fun reportCrash(jsonBody: String): Boolean {
        val request = buildRequest(listOf("crash", "report"), query = emptyMap())
            .newBuilder()
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    continuation.resume(response.isSuccessful)
                }
            })
        }
    }

    private suspend inline fun <reified T> get(
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val request = buildRequest(pathSegments, query, headers)
        val label = "/" + pathSegments.joinToString("/")
        val startedAt = System.currentTimeMillis()

        var attempt = 0
        var lastError: Throwable?
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
                AppLog.w("Api", "GET $label IO error (${classifyNetworkError(error)}), retry ${attempt + 1}/$MAX_RETRIES: ${error.message}")
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
        val category = classifyNetworkError(lastError)
        AppLog.w("Api", "GET $label failed after $attempt retries in ${System.currentTimeMillis() - startedAt}ms ($category)", lastError)
        throw IOException(friendlyNetworkMessage(category), lastError)
    }

    // YouTubeApi 复用同一路径构建(api 前缀判断等),保持 URL 逻辑单源。
    internal fun buildRequest(
        pathSegments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val builder = apiRoot.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val requestBuilder = Request.Builder().url(builder.build()).get()
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        return requestBuilder.build()
    }

    private fun appTokenHeaders(): Map<String, String> =
        if (BuildConfig.APP_API_TOKEN.isNotEmpty()) {
            mapOf("X-App-Token" to BuildConfig.APP_API_TOKEN)
        } else {
            emptyMap()
        }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body.string()
                        if (!it.isSuccessful) {
                            throw apiException(it.code, body)
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
        val request = buildRequest(pathSegments, query)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code == 204) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
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
    }

    private fun apiException(statusCode: Int, body: String): ApiException {
        val apiError = runCatching {
            json.decodeFromString<ApiErrorEnvelope>(body).error
        }.getOrNull()
        return ApiException(
            statusCode = statusCode,
            errorCode = apiError?.code,
            message = apiError?.message ?: statusCode.toFriendlyHttpMessage(),
        )
    }

    private companion object {
        const val MAX_RETRIES = 2
        val RETRY_BACKOFF_MS = longArrayOf(500L, 1500L)
        // 目录接口缓存上限:5MB 足够覆盖若干 provider 的分类列表 + 分页视频列表。
        const val API_CACHE_MAX_BYTES = 5L * 1024 * 1024
    }
}
