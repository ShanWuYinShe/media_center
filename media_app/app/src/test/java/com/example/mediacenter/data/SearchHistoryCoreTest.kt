package com.example.mediacenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryCoreTest {
    @Test
    fun `记录搜索去重且最新在前`() {
        val first = SearchHistoryCore.upsert(emptyList(), "dota")
        val second = SearchHistoryCore.upsert(first, "zoo")
        val duplicate = SearchHistoryCore.upsert(second, " DOTA ")
        assertEquals(listOf("DOTA", "zoo"), duplicate)
    }

    @Test
    fun `超过上限淘汰最旧`() {
        var entries = emptyList<String>()
        repeat(12) { index -> entries = SearchHistoryCore.upsert(entries, "k$index", cap = 8) }
        assertEquals(8, entries.size)
        assertEquals("k11", entries.first())
        assertTrue(entries.none { it == "k0" || it == "k1" || it == "k2" || it == "k3" })
    }

    @Test
    fun `空白关键词不记录`() {
        assertEquals(emptyList<String>(), SearchHistoryCore.upsert(emptyList(), "   "))
        assertEquals(listOf("dota"), SearchHistoryCore.upsert(listOf("dota"), ""))
    }

    @Test
    fun `编解码往返且非法 JSON 回退空列表`() {
        val entries = listOf("dota", "音乐", "zoo")
        assertEquals(entries, SearchHistoryCore.decode(SearchHistoryCore.encode(entries)))
        assertTrue(SearchHistoryCore.decode("not-json").isEmpty())
        assertTrue(SearchHistoryCore.decode(null).isEmpty())
    }
}
