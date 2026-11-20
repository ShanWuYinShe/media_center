package com.example.mediacenter.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 优选 IP 纯逻辑单测(legacy 变体，与 normal 变体同语义)。 */
class PreferredIpPoolTest {
    @Test
    fun `只接管目标域名及其子域`() {
        assertTrue(PreferredIpPool.matchesHost("media.chaoset.com"))
        assertTrue(PreferredIpPool.matchesHost("cdn.media.chaoset.com"))
        assertFalse(PreferredIpPool.matchesHost("i.ytimg.com"))
        assertFalse(PreferredIpPool.matchesHost("notmedia.chaoset.com"))
    }

    @Test
    fun `IPv4 校验`() {
        assertTrue(PreferredIpPool.isValidIpv4("103.31.4.1"))
        assertFalse(PreferredIpPool.isValidIpv4("256.1.1.1"))
        assertFalse(PreferredIpPool.isValidIpv4("01.1.1.1"))
    }

    @Test
    fun `从 JSON 响应抽 IP`() {
        val body = """{"items":["103.31.4.1","104.26.0.1"],"cacheSeconds":3600}"""
        assertEquals(listOf("103.31.4.1", "104.26.0.1"), PreferredIpPool.parseRemoteList(body))
    }

    @Test
    fun `服务端没给候选时仍返回内置池`() {
        assertEquals(PreferredIpPool.BUILTIN, PreferredIpPool.merge(emptyList()))
    }

    @Test
    fun `排序按延迟升序且不可达排最后`() {
        val ranked = PreferredIpPool.rank(
            listOf(
                PreferredIpPool.Probe("slow", 900, true),
                PreferredIpPool.Probe("dead", Long.MAX_VALUE, false),
                PreferredIpPool.Probe("fast", 120, true),
            ),
        )
        assertEquals(listOf("fast", "slow", "dead"), ranked)
    }
}
