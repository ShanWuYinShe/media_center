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
 * 配置了 provider 凭证(如 SESSDATA)时,自动注入 Cookie 以获取会员高分辨率;
 * 未配置时降级为免费 1080P。
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
    // Provider 凭证(由 MediaRepository 从 server 拉取后注入)。
    // 非空时自动附加 Cookie header 并请求更高清晰度。
    @Volatile private var cookieFields: Map<String, String> = emptyMap()

    fun updateCredentials(fields: Map<String, String>) {
        cookieFields = fields
        if (fields.isNotEmpty()) {
            AppLog.i("Bili", "credentials updated: ${fields.size} field(s)")
        }
    }

    /**
     * 解析某 bvid 的可播放直链。返回(播放选项, 多P信息)。
     * 多P信息包含总页数和各页 cid,供 playNext/playPrevious 切换分页用。
     */
    /**
     * 解析某 bvid 的可播放直链。返回(播放选项, 多P信息)。
     * id 支持 "bvid_pN" 格式(多P分条目录),自动提取页码。
     */
    suspend fun resolvePlayback(id: String): PlaybackResult = resolvePlayback(id, preferredQuality())

    suspend fun resolvePlayback(id: String, qn: Int): PlaybackResult = withContext(Dispatchers.IO) {
        val (bvid, pageIndex) = parsePageId(id)
        val viewPayload = fetchView(bvid)
        val pages = viewPayload.data?.pages ?: emptyList()
        val allCids = if (pages.isNotEmpty()) pages.map { it.cid }
            else listOf(viewPayload.data?.cid ?: throw BilibiliApiException("无 cid"))
        val totalPages = allCids.size
        val targetCid = if (pageIndex in allCids.indices) allCids[pageIndex] else allCids.first()
        val pageTitles = pages.map { it.part }
        if (totalPages > 1) {
            AppLog.i("Bili", "multi-page video: $bvid has $totalPages pages, requesting cid=$targetCid (page=$pageIndex) quality=$qn")
        }
        val payload = fetchPlayUrl(bvid, targetCid, quality = qn)
        if (payload.code != 0) throw BilibiliApiException("playurl 接口返回 code=${payload.code}")

        val options = buildPlaybackOptions(bvid, pageIndex, payload)
        if (options.isEmpty()) throw BilibiliApiException("B 站未返回播放流(可能触发风控)")

        val pageDurations = if (pages.isNotEmpty()) pages.map { it.duration } else emptyList()
        PlaybackResult(
            options,
            PlaybackPages(
                currentPage = pageIndex,
                totalPages = totalPages,
                pageCids = allCids,
                pageTitles = pageTitles,
                pageDurations = pageDurations,
            ),
        )
    }

    /**
     * 解析某 bvid 指定分页的播放地址。id 支持 "bvid_pN" 格式。
     */
    suspend fun resolvePagePlayback(id: String, pageIndex: Int): List<PlaybackOption> = withContext(Dispatchers.IO) {
        val bvid = parsePageId(id).first
        val viewPayload = fetchView(bvid)
        val pages = viewPayload.data?.pages ?: emptyList()
        val cids = if (pages.isNotEmpty()) pages.map { it.cid }
            else listOf(viewPayload.data?.cid ?: throw BilibiliApiException("无 cid"))
        if (pageIndex < 0 || pageIndex >= cids.size) {
            throw BilibiliApiException("pageIndex $pageIndex out of bounds (${cids.size} pages)")
        }
        val payload = fetchPlayUrl(bvid, cids[pageIndex], quality = preferredQuality())
        if (payload.code != 0) throw BilibiliApiException("playurl 接口返回 code=${payload.code}")
        val options = buildPlaybackOptions(bvid, pageIndex, payload)
        if (options.isEmpty()) throw BilibiliApiException("B 站未返回播放流(可能触发风控)")
        options
    }

    /**
     * 解析 playurl 响应，构建播放选项。
     *
     * durl 是 B 站针对**所请求 qn** 返回的一条真实可播放流(完整 MP4，已混流音视频),
     * 每个清晰度对应**不同的 durl URL**,不能复用同一条 url。因此这里恒返回**单个选项**,
     * 用所请求 qn 对应的标签标注;accept_quality 列出的其它清晰度需通过 [resolvePagePlayback]
     * 按新 qn 重新解析获取真实流，而不是把同一个 durl 贴上多个 qn 标签(此前实现即如此,
     * 导致「切清晰度」实际播放的始终是同一条流,且每次切换都触发无意义的重新解析)。
     */
    private fun buildPlaybackOptions(bvid: String, pageIndex: Int, payload: BiliPlayUrlResponse): List<PlaybackOption> {
        val data = payload.data
        val first = data.durl.firstOrNull()
        if (first?.url.isNullOrBlank()) return emptyList()
        val qn = data.quality
        // 标签优先用 accept_description 中对应 qn 的描述,缺失时回退到 qualityLabel。
        val label = data.acceptDescription.getOrNull(data.acceptQuality.indexOf(qn))
            ?.takeIf { it.isNotBlank() }
            ?: qualityLabel(qn)
        AppLog.i("Bili", "option: $label qn=$qn")
        return listOf(
            PlaybackOption(
                id = "bili-$bvid-p$pageIndex-q$qn",
                kind = "vod",
                label = label,
                url = first.url,
                mimeType = "video/mp4",
                headers = DIRECT_HEADERS,
                qn = qn,
            ),
        )
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

    private fun preferredQuality(): Int =
        if (cookieFields.isNotEmpty()) PREFERRED_QUALITY_WITH_COOKIE else DEFAULT_QUALITY

    private fun buildCookieHeader(): String {
        if (cookieFields.isEmpty()) return ""
        return cookieFields.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val request = Request.Builder()
            .url(url)
            .apply { DIRECT_HEADERS.forEach { (k, v) -> header(k, v) } }
            .apply {
                val cookie = buildCookieHeader()
                if (cookie.isNotEmpty()) {
                    header("Cookie", cookie)
                }
            }
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
        // 无凭证时默认清晰度:80 = 1080P。
        private const val DEFAULT_QUALITY = 80
        // 有凭证(如 SESSDATA)时首选清晰度:127 = 4K,B 站自动降级到视频可用的最高质量。
        private const val PREFERRED_QUALITY_WITH_COOKIE = 127
        private const val WBI_KEY_TTL_MILLIS = 10L * 60 * 1000

        // B 站反爬要求:必须带 PC 端 User-Agent + Referer 才能拿到 playurl。
        private val DIRECT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com",
            "Origin" to "https://www.bilibili.com",
        )
    }
}

/**
 * 解析 catalog id,支持 "bvid" (单视频) 和 "bvid_pN" (多P分条) 格式。
 * 返回 (真实bvid, 页码)。单视频页码为 0。
 */
internal fun parsePageId(id: String): Pair<String, Int> {
    val idx = id.lastIndexOf("_p")
    if (idx > 0) {
        val suffix = id.substring(idx + 2)
        val pageNum = suffix.toIntOrNull()
        if (pageNum != null && pageNum > 0) {
            return id.substring(0, idx) to (pageNum - 1)
        }
    }
    return id to 0
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
            val duration: Long = 0,
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
        @SerialName("accept_quality")
        val acceptQuality: List<Int> = emptyList(),
        @SerialName("accept_description")
        val acceptDescription: List<String> = emptyList(),
    ) {
        @Serializable
        data class Durl(
            val url: String = "",
            val size: Long = 0,
        )
    }
}


