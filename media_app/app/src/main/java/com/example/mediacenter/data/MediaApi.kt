package com.example.mediacenter.data

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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class MediaApi(
    private val baseUrl: String,
    private val client: OkHttpClient = AppHttpClient.newBuilder()
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

    suspend fun providers(): ProvidersResponse = get(listOf("providers"))

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
        val request = buildRequest(pathSegments, query)
        val label = "/" + pathSegments.joinToString("/")
        val startedAt = System.currentTimeMillis()

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
        AppLog.w("Api", "GET $label failed after ${attempt} retries in ${System.currentTimeMillis() - startedAt}ms", lastError)
        throw IOException(NETWORK_FAILURE_MESSAGE, lastError)
    }

    private fun buildRequest(pathSegments: List<String>, query: Map<String, String>): Request {
        val builder = apiRoot.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return Request.Builder().url(builder.build()).get().build()
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
                        val body = it.body()?.string() ?: throw IOException("响应体为空")
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
        val request = buildRequest(pathSegments, query)
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
