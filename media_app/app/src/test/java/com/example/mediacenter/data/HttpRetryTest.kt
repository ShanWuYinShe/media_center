package com.example.mediacenter.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRetryTest {
    @Test
    fun `5xx 与超时类错误可重试`() {
        assertTrue(500.isTransientHttpStatusCode())
        assertTrue(503.isTransientHttpStatusCode())
        assertTrue(408.isTransientHttpStatusCode())
        assertTrue(425.isTransientHttpStatusCode())
    }

    @Test
    fun `429 是分钟级限流不重试`() {
        // 1 分钟固定窗口内的即时重试必然继续 429,只会放大请求量。
        assertFalse(429.isTransientHttpStatusCode())
    }

    @Test
    fun `业务错误不重试`() {
        assertFalse(400.isTransientHttpStatusCode())
        assertFalse(401.isTransientHttpStatusCode())
        assertFalse(404.isTransientHttpStatusCode())
    }
}
