package com.example.mediacenter.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class MediaApi(
    private val baseUrl: String,
    // connectTimeout 15s 与 readTimeout 对齐：Android TV 模拟器冷启动 + emulator
    // 网络栈刚就绪时,三次握手偶尔超过 5s,过紧的超时会直接抛 "Failed to connect"。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun search(query: String, page: Int = 1, pageSize: Int = 18): SearchResponse = get(
        pathSegments = listOf("api", "search"),
        query = mapOf(
            "q" to query,
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    suspend fun searchCategory(
        categoryId: String,
        query: String,
        page: Int = 1,
        pageSize: Int = 18,
    ): SearchResponse = get(
        pathSegments = listOf("api", "search"),
        query = mapOf(
            "category" to categoryId,
            "q" to query,
            "page" to page.toString(),
            "pageSize" to pageSize.toString(),
        ),
    )

    suspend fun categories(): CategoryResponse = get(pathSegments = listOf("api", "categories"))

    suspend fun details(id: String): VideoDetails = get(
        pathSegments = listOf("api", "videos", id),
    )

    private suspend inline fun <reified T> get(
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val builder = baseUrl.toHttpUrl().newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build()).get().build()

        // 对 IOException 做最多 2 次指数退避重试：覆盖冷启动握手抖动,
        // 但不掩盖业务/反序列化错误(后者不抛 IOException)。
        var attempt = 0
        var lastError: IOException? = null
        while (true) {
            try {
                return@withContext client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.peekBody(512).string()}")
                    }
                    json.decodeFromString<T>(response.requiredBody())
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt >= MAX_RETRIES) break
                // 退避 500ms / 1500ms,总附加等待 ≤ 2s,不会让用户感觉卡顿。
                kotlinx.coroutines.delay(RETRY_BACKOFF_MS[attempt])
                attempt++
            }
        }
        throw lastError ?: IOException("未知网络错误")
    }

    private fun Response.requiredBody(): String {
        return body?.string() ?: throw IOException("响应体为空")
    }

    private companion object {
        const val MAX_RETRIES = 2
        val RETRY_BACKOFF_MS = longArrayOf(500L, 1500L)
    }
}
