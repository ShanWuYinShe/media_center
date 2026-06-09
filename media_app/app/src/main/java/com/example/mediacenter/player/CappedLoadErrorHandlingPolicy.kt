package com.example.mediacenter.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
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
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        if (loadErrorInfo.errorCount > maxRetries) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
