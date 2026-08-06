package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException

class MediaApi(
    val baseUrl: String,
    private val client: OkHttpClient = AppHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(15))
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

    private fun buildRequest(
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
    }
}

/**
 * 对网络层 [IOException] 做分类,便于诊断"网络错误"的真实原因。
 *
 * 电视盒子最常见的失败是 TLS 握手失败(设备时钟漂移导致证书校验不过,
 * 或老 Android 4.4 的 TLS 栈与服务端不兼容),其次是连接超时、DNS 失败。
 * 此前这些原因全部被笼统报成"网络连接失败",无法区分。这里按异常类型
 * 归类,既用于日志(便于远程诊断),也驱动 [friendlyNetworkMessage] 给出
 * 更有针对性的用户提示。
 */
private enum class NetworkErrorCategory {
    TLS_HANDSHAKE,
    CONNECT_TIMEOUT,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    GENERIC_NETWORK,
}

private fun classifyNetworkError(error: Throwable?): NetworkErrorCategory {
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
            is SSLException -> return NetworkErrorCategory.TLS_HANDSHAKE
            is SocketTimeoutException -> return NetworkErrorCategory.CONNECT_TIMEOUT
            is UnknownHostException -> return NetworkErrorCategory.DNS_FAILURE
            is ConnectException -> return NetworkErrorCategory.CONNECTION_REFUSED
        }
        // 异常信息兜底:部分实现把根因塞进 message 而非异常类型。
        val msg = cause.message.orEmpty()
        when {
            msg.contains("SSL", ignoreCase = true) ||
                msg.contains("TLS", ignoreCase = true) ||
                msg.contains("handshake", ignoreCase = true) ||
                msg.contains("CertificateException", ignoreCase = true) ||
                msg.contains("Chain validation", ignoreCase = true) ->
                return NetworkErrorCategory.TLS_HANDSHAKE
            msg.contains("timed out", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ->
                return NetworkErrorCategory.CONNECT_TIMEOUT
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ||
                msg.contains("getaddrinfo", ignoreCase = true) ->
                return NetworkErrorCategory.DNS_FAILURE
            msg.contains("refused", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ->
                return NetworkErrorCategory.CONNECTION_REFUSED
        }
        cause = cause.cause
    }
    return NetworkErrorCategory.GENERIC_NETWORK
}

/**
 * 将分类后的网络错误翻译成面向用户的提示。
 *
 * 对 TLS 握手失败额外提示"可能是设备时间不准"——这是电视盒子最高频的
 * 根因(RTC 没电池,断电后时钟漂移),用户手动校准时间即可解决,无需网络。
 */
private fun friendlyNetworkMessage(category: NetworkErrorCategory): String = when (category) {
    NetworkErrorCategory.TLS_HANDSHAKE ->
        "安全连接失败，请检查设备的系统时间是否准确后重试"
    NetworkErrorCategory.CONNECT_TIMEOUT ->
        "连接超时，请检查网络后重试"
    NetworkErrorCategory.DNS_FAILURE ->
        "无法解析服务器地址，请检查网络/DNS 后重试"
    NetworkErrorCategory.CONNECTION_REFUSED ->
        "服务器拒绝连接，请稍后重试"
    NetworkErrorCategory.GENERIC_NETWORK ->
        "网络连接失败，请检查网络后重试"
}

class ApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
) : Exception(message) {
    val isTransient: Boolean
        get() = statusCode.isTransientHttpStatusCode()
}

@Serializable
private data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
)

@Serializable
private data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
)
