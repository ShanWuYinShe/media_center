package com.example.mediacenter.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 优选 IP 的纯逻辑单测:解析、校验、合并、排序、host 白名单。
 *
 * 这几条是"加速手段不能反过来弄坏可用性"的底线:host 白名单错了会劫持其它域名，
 * 解析/排序错了会一直用一个坏 IP，兜底失败会让 App 完全连不上服务端。
 */
class PreferredIpPoolTest {
    @Test
    fun `只接管目标域名及其子域`() {
        assertTrue(PreferredIpPool.matchesHost("media.chaoset.com"))
        assertTrue(PreferredIpPool.matchesHost("MEDIA.Chaoset.COM"))
        assertTrue(PreferredIpPool.matchesHost("media.chaoset.com."))
        assertTrue(PreferredIpPool.matchesHost("cdn.media.chaoset.com"))
        // 其它域名必须原样交给系统 DNS:劫持会直接造成连错站点/403。
        assertFalse(PreferredIpPool.matchesHost("i.ytimg.com"))
        assertFalse(PreferredIpPool.matchesHost("www.bilibili.com"))
        assertFalse(PreferredIpPool.matchesHost("chaoset.com"))
        assertFalse(PreferredIpPool.matchesHost("notmedia.chaoset.com"))
        assertFalse(PreferredIpPool.matchesHost(""))
    }

    @Test
    fun `IPv4 字面量严格校验`() {
        listOf("1.1.1.1", "103.31.4.1", "255.255.255.255", "104.16.0.1").forEach {
            assertTrue(it + " should be valid", PreferredIpPool.isValidIpv4(it))
        }
        listOf("", "1.1.1", "1.1.1.1.1", "256.1.1.1", "1.1.1.256", "01.1.1.1", "a.b.c.d")
            .forEach { assertFalse(it + " should be invalid", PreferredIpPool.isValidIpv4(it)) }
    }

    @Test
    fun `从服务端 JSON 响应里抽出 IP`() {
        // 服务端下发的是 {"items":[...]}，解析器不能依赖固定结构(字段会演进)。
        val body = """{"items":["103.31.4.1","104.26.0.1"],"updatedAt":"2026-11-20T14:00:00Z","cacheSeconds":3600}"""
        assertEquals(listOf("103.31.4.1", "104.26.0.1"), PreferredIpPool.parseRemoteList(body))
    }

    @Test
    fun `从纯文本列表里抽出 IP 并去重`() {
        val body = listOf("# 注释行", "103.31.4.1", "103.31.4.1", "104.26.0.1  # 行尾注释", "not-an-ip").joinToString("\n")
        assertEquals(listOf("103.31.4.1", "104.26.0.1"), PreferredIpPool.parseRemoteList(body))
    }

    @Test
    fun `空响应返回空列表`() {
        assertTrue(PreferredIpPool.parseRemoteList("").isEmpty())
        assertTrue(PreferredIpPool.parseRemoteList("{}").isEmpty())
    }

    @Test
    fun `合并时服务端候选优先且内置池兜底`() {
        val merged = PreferredIpPool.merge(listOf("10.0.0.1"), builtin = listOf("10.0.0.1", "10.0.0.2"))
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), merged)
    }

    @Test
    fun `服务端没给候选时仍返回内置池`() {
        // 关键兜底:首启动/端点异常/断网时，不能因为拿不到池子就退化成系统 DNS。
        val fallback = PreferredIpPool.merge(emptyList())
        assertEquals(PreferredIpPool.BUILTIN, fallback)
        assertTrue(fallback.isNotEmpty())
    }

    @Test
    fun `候选池按上限截断`() {
        val many = (1..100).map { "10.0." + (it / 256) + "." + (it % 256) }
        assertTrue(PreferredIpPool.merge(many).size <= PreferredIpPool.MAX_CANDIDATES)
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

    @Test
    fun `排序稳定且空输入返回空`() {
        val ranked = PreferredIpPool.rank(
            listOf(
                PreferredIpPool.Probe("a", 100, true),
                PreferredIpPool.Probe("b", 100, true),
            ),
        )
        assertEquals(listOf("a", "b"), ranked)
        assertTrue(PreferredIpPool.rank(emptyList()).isEmpty())
    }

    @Test
    fun `IP 转地址不走 DNS 且非法项被跳过`() {
        val addresses = PreferredIpPool.toAddresses(listOf("103.31.4.1", "not-an-ip", "104.26.0.1"))
        assertEquals(2, addresses.size)
        assertEquals("103.31.4.1", addresses.first().hostAddress)
    }

    @Test
    fun `编码后可被同一解析器读回`() {
        val ips = listOf("103.31.4.1", "104.26.0.1")
        assertEquals(ips, PreferredIpPool.parseRemoteList(PreferredIpPool.encode(ips)))
    }
}
