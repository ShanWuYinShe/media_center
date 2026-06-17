package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BilibiliApiException(message: String) : Exception(message)

/**
 * B 站官方 API 客户端:让 App 端拿到 catalog 的 bvid 后**自己**解析播放地址,
 * 避免 server 端做上游调用(限流 / 带宽压力 / 重复签名)。
 *
 * **调用链**:
 *  1. `/x/web-interface/nav` 拿 WBI 签名密钥(`imgKey` + `subKey`),本地缓存 10 分钟
 *  2. `/x/web-interface/wbi/view` 拿 cid(分 P 标识)
 *  3. `/x/player/wbi/playurl` 用 cid + bvid 拿 durl(真实播放直链)
 *
 * 第 1、2、3 步均需 WBI 签名(见 [signWbiParams])。
 * 不需要 SESSDATA cookie——只支持免费可见视频(与 server 端原行为一致)。
 */
class BilibiliApi(
    private val client: OkHttpClient = AppHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val keysMutex = Mutex()
    @Volatile private var imgKey: String = ""
    @Volatile private var subKey: String = ""
    @Volatile private var keyExpiryMillis: Long = 0L

    /**
     * 解析某 bvid 的可播放直链。返回(播放选项, 多P信息)。
     * 多P信息包含总页数和各页 cid,供 playNext/playPrevious 切换分页用。
     */
    suspend fun resolvePlayback(bvid: String): PlaybackResult = withContext(Dispatchers.IO) {
        val viewPayload = fetchView(bvid)
        val pages = viewPayload.data?.pages ?: emptyList()
        val firstCid = pages.firstOrNull()?.cid ?: viewPayload.data?.cid ?: throw BilibiliApiException("无 cid")
        val totalPages = maxOf(pages.size, 1)
        val pageCids = if (pages.isNotEmpty()) pages.map { it.cid } else listOf(firstCid)
        val pageTitles = pages.map { it.part }
        if (totalPages > 1) {
            AppLog.i("Bili", "multi-page video: $bvid has $totalPages pages")
        }
        val payload = fetchPlayUrl(bvid, firstCid, quality = DEFAULT_QUALITY)
        if (payload.code != 0) throw BilibiliApiException("playurl 接口返回 code=${payload.code}")
        val durlList = payload.data.durl
        if (durlList.isEmpty()) throw BilibiliApiException("B 站未返回播放流(可能触发风控)")
        val first = durlList.first()
        PlaybackResult(
            listOf(PlaybackOption(
                id = "bili-$bvid-q${payload.data.quality}",
                kind = "vod",
                label = qualityLabel(payload.data.quality),
                url = first.url,
                mimeType = "video/mp4",
                headers = DIRECT_HEADERS,
            )),
            PlaybackPages(currentPage = 0, totalPages = totalPages, pageCids = pageCids, pageTitles = pageTitles),
        )
    }

    /**
     * 解析某 bvid 指定分页的播放地址。用于 playNext 切换多P时按需拉取。
     */
    suspend fun resolvePagePlayback(bvid: String, pageIndex: Int): List<PlaybackOption> = withContext(Dispatchers.IO) {
        val viewPayload = fetchView(bvid)
        val pages = viewPayload.data?.pages ?: emptyList()
        val cids = if (pages.isNotEmpty()) pages.map { it.cid }
            else listOf(viewPayload.data?.cid ?: throw BilibiliApiException("无 cid"))
        if (pageIndex < 0 || pageIndex >= cids.size) {
            throw BilibiliApiException("pageIndex $pageIndex out of bounds (${cids.size} pages)")
        }
        val payload = fetchPlayUrl(bvid, cids[pageIndex], quality = DEFAULT_QUALITY)
        if (payload.code != 0) throw BilibiliApiException("playurl 接口返回 code=${payload.code}")
        val durlList = payload.data.durl
        if (durlList.isEmpty()) throw BilibiliApiException("B 站未返回播放流(可能触发风控)")
        val first = durlList.first()
        listOf(PlaybackOption(
            id = "bili-$bvid-p$pageIndex-q${payload.data.quality}",
            kind = "vod",
            label = qualityLabel(payload.data.quality),
            url = first.url,
            mimeType = "video/mp4",
            headers = DIRECT_HEADERS,
        ))
    }

    // 调 B 站 view API 拿 bvid/cid/pages。
    private suspend fun fetchView(bvid: String): BiliViewResponse {
        val keys = ensureWbiKeys()
        val signed = signWbiParams(mapOf("bvid" to bvid), keys.first, keys.second)
        val url = buildUrl("https://api.bilibili.com/x/web-interface/wbi/view", signed)
        val payload = getJson<BiliViewResponse>(url)
        if (payload.code != 0 || payload.data == null || payload.data.bvid.isEmpty()) {
            throw BilibiliApiException("view 接口返回 code=${payload.code} 找不到 bvid=$bvid")
        }
        if (payload.data.cid == 0L) {
            throw BilibiliApiException("view 接口未返回 cid(可能视频被删/审核)")
        }
        return payload
    }

    private suspend fun fetchPlayUrl(bvid: String, cid: Long, quality: Int): BiliPlayUrlResponse {
        val keys = ensureWbiKeys()
        val signed = signWbiParams(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "fnval" to "0",
                "qn" to quality.toString(),
                "fourk" to "1",
            ),
            keys.first,
            keys.second,
        )
        val url = buildUrl("https://api.bilibili.com/x/player/wbi/playurl", signed)
        return getJson<BiliPlayUrlResponse>(url)
    }

    private suspend fun ensureWbiKeys(): Pair<String, String> {
        val now = System.currentTimeMillis()
        if (now < keyExpiryMillis && imgKey.isNotEmpty() && subKey.isNotEmpty()) {
            return imgKey to subKey
        }
        return keysMutex.withLock {
            // double-check:其他并发请求可能已刷新
            if (System.currentTimeMillis() < keyExpiryMillis && imgKey.isNotEmpty() && subKey.isNotEmpty()) {
                return@withLock imgKey to subKey
            }
            val payload = getJson<BiliNavResponse>("https://api.bilibili.com/x/web-interface/nav")
            val newImg = extractKeyFromUrl(payload.data?.wbiImg?.imgUrl ?: "")
            val newSub = extractKeyFromUrl(payload.data?.wbiImg?.subUrl ?: "")
            if (newImg.isEmpty() || newSub.isEmpty()) {
                throw BilibiliApiException("无法从 nav 接口拿到 WBI 密钥(code=${payload.code})")
            }
            imgKey = newImg
            subKey = newSub
            keyExpiryMillis = System.currentTimeMillis() + WBI_KEY_TTL_MILLIS
            AppLog.i("Bili", "refreshed WBI keys (ttl=${WBI_KEY_TTL_MILLIS}ms)")
            newImg to newSub
        }
    }

    private fun extractKeyFromUrl(url: String): String {
        val file = url.substringAfterLast("/")
        return file.substringBeforeLast(".")
    }

    private fun qualityLabel(qn: Int): String = when {
        qn >= 120 -> "4K"
        qn >= 116 -> "1080P60"
        qn >= 112 -> "1080P+"
        qn >= 80 -> "1080P"
        qn >= 64 -> "720P"
        qn >= 32 -> "480P"
        else -> "360P"
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val parsed = HttpUrl.parse(base) ?: throw BilibiliApiException("invalid url: $base")
        val builder = parsed.newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val request = Request.Builder()
            .url(url)
            .apply { DIRECT_HEADERS.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body()?.string() ?: throw BilibiliApiException("B 站响应体为空")
            if (!response.isSuccessful) {
                throw BilibiliApiException("B 站 API ${response.code()}: ${body.take(200)}")
            }
            return json.decodeFromString<T>(body)
        }
    }

    companion object {
        // 默认清晰度:80 = 1080P(B 站大会员更高清晰度需 SESSDATA cookie,本客户端不支持)。
        private const val DEFAULT_QUALITY = 80
        private const val WBI_KEY_TTL_MILLIS = 10L * 60 * 1000

        // B 站反爬要求:必须带 PC 端 User-Agent + Referer 才能拿到 playurl。
        private val DIRECT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com",
            "Origin" to "https://www.bilibili.com",
        )
    }
}

@Serializable
private data class BiliNavResponse(
    val code: Int = 0,
    val data: NavData? = null,
) {
    @Serializable
    data class NavData(
        @SerialName("wbi_img")
        val wbiImg: WbiImg? = null,
    ) {
        @Serializable
        data class WbiImg(
            @SerialName("img_url")
            val imgUrl: String = "",
            @SerialName("sub_url")
            val subUrl: String = "",
        )
    }
}

@Serializable
private data class BiliViewResponse(
    val code: Int = 0,
    val data: ViewData? = null,
) {
    @Serializable
    data class ViewData(
        val bvid: String = "",
        val cid: Long = 0,
        val pages: List<PageData> = emptyList(),
    ) {
        @Serializable
        data class PageData(
            val cid: Long = 0,
            val part: String = "",
        )
    }
}

@Serializable
private data class BiliPlayUrlResponse(
    val code: Int = 0,
    val data: PlayUrlData = PlayUrlData(),
) {
    @Serializable
    data class PlayUrlData(
        val quality: Int = 0,
        val durl: List<Durl> = emptyList(),
    ) {
        @Serializable
        data class Durl(
            val url: String = "",
            val size: Long = 0,
        )
    }
}
