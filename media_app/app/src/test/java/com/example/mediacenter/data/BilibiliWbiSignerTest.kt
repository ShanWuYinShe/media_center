package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliWbiSignerTest {
    // imgKey/subKey 按真实 nav 接口形态取 32 位十六进制。
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `签名结果保留原参数并追加 wts 与 w_rid`() {
        val signed = signWbiParams(mapOf("bvid" to "BV1xx411c7mD", "qn" to "80"), imgKey, subKey)
        assertEquals("BV1xx411c7mD", signed["bvid"])
        assertEquals("80", signed["qn"])
        // wts 是秒级时间戳,与当前时间差应在合理范围内(容忍测试机时钟慢 1 分钟)
        val wts = signed.getValue("wts").toLong()
        val now = System.currentTimeMillis() / 1000L
        assertTrue("wts=$wts 应接近 now=$now", wts in (now - 60)..(now + 5))
    }

    @Test
    fun `w_rid 是 32 位小写十六进制 MD5`() {
        val signed = signWbiParams(mapOf("bvid" to "BV1xx411c7mD"), imgKey, subKey)
        val wRid = signed.getValue("w_rid")
        assertTrue("w_rid=$wRid 应为 32 位 hex", wRid.length == 32 && wRid.all { it in "0123456789abcdef" })
    }

    @Test
    fun `encodeWbiValue 对齐参考实现编码`() {
        // 空格按 %20(不是 +),~ 保留原字符(不是 %7E)
        assertEquals("hello%20world", encodeWbiValue("hello world"))
        assertEquals("a~b", encodeWbiValue("a~b"))
        // 字面 + 必须编码为 %2B(与空格的 + 占位区分)
        assertEquals("a%2Bb", encodeWbiValue("a+b"))
    }

    @Test
    fun `不同参数产生不同签名`() {
        val a = signWbiParams(mapOf("bvid" to "BV1xx411c7mD"), imgKey, subKey)
        val b = signWbiParams(mapOf("bvid" to "BV1xx411c7mD", "cid" to "1"), imgKey, subKey)
        assertNotEquals(a["w_rid"], b["w_rid"])
    }
}
