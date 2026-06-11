package com.example.mediacenter.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
internal class CappedLoadErrorHandlingPolicy(
    private val maxRetries: Int,
) : DefaultLoadErrorHandlingPolicy(maxRetries) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (loadErrorInfo.errorCount > maxRetries) return null
        // HTTP 层错误（401/403/502 等）不重试——同一签名 URL 再请求也无意义，
        // 立即上抛让 ViewModel 刷新签名地址。
        if (loadErrorInfo.exception.isHttpError) return null
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        if (loadErrorInfo.errorCount > maxRetries) return C.TIME_UNSET
        if (loadErrorInfo.exception.isHttpError) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

// 递归检测异常链中是否包含 HTTP 状态码错误。
// HttpDataSource.InvalidResponseCodeException 表示服务端已响应但返回非 2xx——
// 对同一 URL 重试无法修复，应由 ViewModel 刷新签名地址。
@OptIn(UnstableApi::class)
private val Throwable.isHttpError: Boolean
    get() = this is HttpDataSource.InvalidResponseCodeException
        || (cause?.isHttpError == true)
