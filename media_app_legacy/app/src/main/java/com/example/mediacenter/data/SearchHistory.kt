package com.example.mediacenter.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 最近搜索关键词的纯逻辑(不依赖 Android,可 JVM 单测)。
 *
 * 目的:搜索页空态先展示用户上次搜过的词,一键重搜;老人不用每次重新用遥控器
 * 逐字输入。去重(忽略大小写)、最新在前、有容量上限。
 */
object SearchHistoryCore {
    const val DEFAULT_CAP = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun encode(entries: List<String>): String = json.encodeToString(serializer, entries)

    fun upsert(entries: List<String>, query: String, cap: Int = DEFAULT_CAP): List<String> {
        val keyword = query.trim()
        if (keyword.isEmpty() || cap <= 0) return entries
        return (listOf(keyword) + entries.filterNot { it.equals(keyword, ignoreCase = true) }).take(cap)
    }
}

/**
 * 最近搜索关键词:本地 SharedPreferences + JSON,条数有上限。
 */
object SearchHistory {
    private const val PREFS = "search_history"
    private const val KEY = "entries"

    fun entries(context: Context): List<String> =
        SearchHistoryCore.decode(prefs(context).getString(KEY, null))

    fun record(context: Context, query: String) {
        val updated = SearchHistoryCore.upsert(entries(context), query)
        prefs(context).edit { putString(KEY, SearchHistoryCore.encode(updated)) }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
