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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class MediaApi(
    val baseUrl: String,
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

    // HTTPS 握手失败时的明文降级通道:
    // Android 4.4.2 的老 TLS 栈与 Cloudflare 的现代 TLS 配置(TLS1.3 + ECDSA-only 套件)
    // 无法协商,trust-all 也救不了(失败在套件/协议协商,不在证书校验)。
    // 服务端放行 HTTP 后,这里用明文 HTTP 重试一次,保证老设备 100% 能连上。
    // 仅当 HTTPS 失败原因是 TLS 握手时触发,不影响正常 HTTPS 流量。
    private val httpClient: OkHttpClient? = if (apiRoot.toString().startsWith("https://", ignoreCase = true)) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false) // 服务端/Cloudflare 若仍 301,不跟随,直接报错由上层处理
            .build()
    } else {
        null
    }
    private val httpApiRoot: HttpUrl? = httpClient?.let {
        apiRoot.newBuilder().scheme("http").build()
    }

    /** 是否已降级到明文 HTTP(供日志/诊断) */
    @Volatile
    var httpFallbackActive: Boolean = false
        private set

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

    fun resolveApiUrl(pathSegments: List<String>): String {
        // 已降级到明文 HTTP 时,播放/缩略图等派生 URL 也用 HTTP,保持一致。
        val root = if (httpFallbackActive) httpApiRoot ?: apiRoot else apiRoot
        val builder = root.newBuilder()
        if (!baseUrlIncludesApiPrefix) {
            builder.addPathSegment("api")
        }
        pathSegments.forEach(builder::addPathSegment)
        return builder.build().toString()
    }

    private suspend inline fun <reified T> get(
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val label = "/" + pathSegments.joinToString("/")
        val startedAt = System.currentTimeMillis()

        var attempt = 0
        var lastError: Throwable? = null
        AppLog.d("Api", "GET $label query=${query.keys}")
        while (true) {
            try {
                val body = execute(buildRequest(apiRoot, pathSegments, query, headers))
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
        // HTTPS 重试耗尽:若是 TLS 握手失败(老设备与 Cloudflare TLS 配置不兼容),
        // 降级到明文 HTTP 再试一次(服务端需放行 HTTP,见 MediaApi 类注释)。
        if (classifyNetworkError(lastError) == NetworkErrorCategory.TLS_HANDSHAKE) {
            val httpRoot = httpApiRoot
            if (httpRoot != null) {
                val httpRequest = buildRequest(httpRoot, pathSegments, query, headers)
                AppLog.w("Api", "GET $label HTTPS TLS failed, falling back to plain HTTP")
                httpFallbackActive = true
                try {
                    val body = execute(httpRequest, useHttp = true)
                    val result = withContext(Dispatchers.Default) {
                        json.decodeFromString<T>(body)
                    }
                    AppLog.w("Api", "GET $label ok via HTTP fallback in ${System.currentTimeMillis() - startedAt}ms")
                    return result
                } catch (httpError: Throwable) {
                    AppLog.w("Api", "GET $label HTTP fallback also failed: ${httpError.message}", httpError)
                    lastError = httpError
                }
            }
        }
        val category = classifyNetworkError(lastError)
        AppLog.w("Api", "GET $label failed after ${attempt} retries in ${System.currentTimeMillis() - startedAt}ms ($category)", lastError)
        throw IOException(friendlyNetworkMessage(lastError), lastError)
    }

    private fun buildRequest(
        root: HttpUrl,
        pathSegments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): Request {
        val builder = root.newBuilder()
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

    private suspend fun execute(request: Request, useHttp: Boolean = false): String = suspendCancellableCoroutine { continuation ->
        val call = (if (useHttp) httpClient ?: client else client).newCall(request)
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
        val request = buildRequest(apiRoot, pathSegments, query, emptyMap())
        return try {
            requestBody(request, useHttp = false)
        } catch (error: IOException) {
            // HTTPS 握手失败时降级 HTTP 重试一次(见 MediaApi 类注释)。
            if (classifyNetworkError(error) == NetworkErrorCategory.TLS_HANDSHAKE) {
                val httpRoot = httpApiRoot
                if (httpRoot != null) {
                    val httpRequest = buildRequest(httpRoot, pathSegments, query, emptyMap())
                    AppLog.w("Api", "GET optional ${pathSegments.joinToString("/")} HTTPS TLS failed, falling back to plain HTTP")
                    httpFallbackActive = true
                    return requestBody(httpRequest, useHttp = true)
                }
            }
            throw error
        }
    }

    private suspend fun requestBody(request: Request, useHttp: Boolean): String? = suspendCancellableCoroutine { continuation ->
        val call = (if (useHttp) httpClient ?: client else client).newCall(request)
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
 *
 * **根因优先**:SSL 层异常(SSLHandshakeException 等)只是外壳,真正的根因
 * 在 cause 链深处(如 Android 4.4 直接抛出的 `CertificateNotYetValidException`,
 * 或 `No cipher suites in common`)。因此先遍历整条 cause 链做精确分类,
 * 再落到通用的 SSL / 超时 / DNS 分类,避免把"证书时间不对"误报成泛化的
 * "检查系统时间"猜测。
 */
internal enum class NetworkErrorCategory {
    TLS_CERT_TIME,
    TLS_CIPHER_MISMATCH,
    TLS_CHAIN_UNTRUSTED,
    TLS_HANDSHAKE,
    CONNECT_TIMEOUT,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    GENERIC_NETWORK,
}

internal fun classifyNetworkError(error: Throwable?): NetworkErrorCategory {
    // 展开整条 cause 链,按"根因优先"逐项匹配。
    val chain = generateSequence(error) { it.cause }.toList()
    fun anyMessage(marker: String): Boolean =
        chain.any { it.message?.contains(marker, ignoreCase = true) == true }

    // 1) 证书时间类:设备时钟漂移导致证书"尚未生效 / 已过期"。
    if (chain.any { it is CertificateNotYetValidException || it is CertificateExpiredException }) {
        return NetworkErrorCategory.TLS_CERT_TIME
    }
    if (anyMessage("not yet valid") || anyMessage("NotBefore") ||
        anyMessage("NotAfter") || anyMessage("expired")
    ) {
        return NetworkErrorCategory.TLS_CERT_TIME
    }
    // 2) 密码套件不匹配:老设备(如 Android 4.4)与服务端没有共同套件。
    if (anyMessage("no cipher suites") || anyMessage("no cipher") ||
        anyMessage("cipher suites in common")
    ) {
        return NetworkErrorCategory.TLS_CIPHER_MISMATCH
    }
    // 3) 证书链不受信任:签名 / 锚点校验不过(区别于纯时间问题)。
    if (anyMessage("unable to find valid certification path") ||
        anyMessage("not trusted") || anyMessage("untrusted") ||
        anyMessage("certificate_unknown")
    ) {
        return NetworkErrorCategory.TLS_CHAIN_UNTRUSTED
    }
    // 4) 其余 SSL / TLS 握手失败(保留原兜底,但不再是"猜测时间"的唯一出口)。
    if (chain.any { it is SSLException }) {
        return NetworkErrorCategory.TLS_HANDSHAKE
    }
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
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
 * 分类是**确定性的**:证书时间类只在 cause 链里出现"未生效 / 已过期"信号时
 * 才提示时间;证书不受信任 / 密码套件不匹配分别给出各自的确定结论,不再把
 * 所有 TLS 失败一律归咎于系统时间。
 */
internal fun friendlyNetworkMessage(error: Throwable?): String = when (classifyNetworkError(error)) {
    NetworkErrorCategory.TLS_CERT_TIME ->
        "安全连接失败：服务器证书在本设备当前时间无效，确定是设备系统时间不准确。请在系统设置中校准时间后重试"
    NetworkErrorCategory.TLS_CIPHER_MISMATCH ->
        "安全连接失败：设备系统版本过旧，无法与服务器建立安全连接"
    NetworkErrorCategory.TLS_CHAIN_UNTRUSTED ->
        "安全连接失败：服务器证书不受本设备信任，请检查网络环境（代理/劫持）后重试"
    NetworkErrorCategory.TLS_HANDSHAKE ->
        "安全连接失败，请检查网络后重试"
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
