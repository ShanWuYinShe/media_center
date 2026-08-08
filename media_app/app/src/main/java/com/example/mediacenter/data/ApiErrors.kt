package com.example.mediacenter.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.Serializable

/**
 * 网络错误分类与用户提示:从 MediaApi.kt 拆分。
 *
 * 电视盒子最常见的失败是 TLS 握手失败(设备时钟漂移导致证书校验不过,
 * 或老 Android 4.4 的 TLS 栈与服务端不兼容),其次是连接超时、DNS 失败。
 * 此处按异常类型归类,既用于日志(便于远程诊断),也驱动
 * [friendlyNetworkMessage] 给出更有针对性的用户提示。
 */
/**
 * 对网络层 [IOException] 做分类,便于诊断"网络错误"的真实原因。
 *
 * 电视盒子最常见的失败是 TLS 握手失败(设备时钟漂移导致证书校验不过,
 * 或老 Android 4.4 的 TLS 栈与服务端不兼容),其次是连接超时、DNS 失败。
 * 此前这些原因全部被笼统报成"网络连接失败",无法区分。这里按异常类型
 * 归类,既用于日志(便于远程诊断),也驱动 [friendlyNetworkMessage] 给出
 * 更有针对性的用户提示。
 */
internal enum class NetworkErrorCategory {
    TLS_HANDSHAKE,
    CONNECT_TIMEOUT,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    GENERIC_NETWORK,
}

internal fun classifyNetworkError(error: Throwable?): NetworkErrorCategory {
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
internal fun friendlyNetworkMessage(category: NetworkErrorCategory): String = when (category) {
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
internal data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
)

@Serializable
internal data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
)
