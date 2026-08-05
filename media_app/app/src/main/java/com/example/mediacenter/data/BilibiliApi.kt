package com.example.mediacenter.data

import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.Duration

class BilibiliApiException(override val message: String) : Exception(message)

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
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(15))
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
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
        // 目录里的 bvid_pN 可能过期(视频被删页):越界时报错,不静默播放第 1 页
        // (与 legacy 变体行为一致,避免 UI 显示页码与实际播放内容不符)。
        if (pageIndex !in allCids.indices) {
            throw BilibiliApiException("页面越界:共 $totalPages 页,请求第 ${pageIndex + 1} 页")
        }
        val targetCid = allCids[pageIndex]
        val pageTitles = pages.map { it.part ?: "" }
        if (totalPages > 1) {
            AppLog.i("Bili", "multi-page video: $bvid has $totalPages pages, requesting cid=$targetCid (page=$pageIndex) quality=$qn")
        }
        val payload = fetchPlayUrl(bvid, targetCid, quality = qn)
        if (payload.code != 0) throw BilibiliApiException("playurl 接口返回 code=${payload.code}")

        val (options, qualities) = buildPlaybackOptions(bvid, pageIndex, payload)
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
            availableQualities = qualities,
        )
    }

    /**
     * 解析 playurl 响应,构建播放选项与可切换清晰度列表。
     *
     * 不使用 DASH(.m4s 分片不能直接作为 Progressive 流播放);播放统一使用
     * durl(完整 MP4,已混流音视频)。durl 只有请求 qn 对应的**一条**真实流,
     * 因此 options 恒为单个选项;accept_quality/accept_description 描述的
     * 其它清晰度不能复用同一条 url,而是通过 [resolvePlayback] 按新 qn 重新
     * 解析获取,这里只把它们作为可切换列表暴露给 UI。
     */
    private fun buildPlaybackOptions(
        bvid: String,
        pageIndex: Int,
        payload: BiliPlayUrlResponse,
    ): Pair<List<PlaybackOption>, List<QualityOption>> {
        val data = payload.data ?: return emptyList<PlaybackOption>() to emptyList<QualityOption>()
        val durlUrl = data.durl?.firstOrNull()?.url ?: return emptyList<PlaybackOption>() to buildAvailableQualities(data)
        val option = PlaybackOption(
            id = "bili-$bvid-p$pageIndex-q${data.quality}",
            kind = "vod",
            label = qualityLabel(data.quality),
            url = durlUrl,
            mimeType = "video/mp4",
            headers = DIRECT_HEADERS,
            qn = data.quality,
        )
        return listOf(option) to buildAvailableQualities(data)
    }

    private fun buildAvailableQualities(data: BiliPlayUrlResponse.PlayUrlData): List<QualityOption> {
        val acceptQn = data.acceptQuality ?: return emptyList()
        val acceptDesc = data.acceptDescription ?: return emptyList()
        if (acceptQn.isEmpty() || acceptQn.size != acceptDesc.size) return emptyList()
        return acceptQn.zip(acceptDesc)
            .sortedByDescending { it.first }
            .map { (qn, desc) -> QualityOption(qn, desc.ifBlank { qualityLabel(qn) }) }
    }

    // 调 B 站 view API 拿 bvid/cid/pages。
    private suspend fun fetchView(bvid: String): BiliViewResponse {
        val keys = ensureWbiKeys()
        val signed = signWbiParams(mapOf("bvid" to bvid), keys.first, keys.second)
        val url = buildUrl("https://api.bilibili.com/x/web-interface/wbi/view", signed)
        val payload = getJson<BiliViewResponse>(url)
        val data = payload.data ?: throw BilibiliApiException("view 接口返回 code=${payload.code} 找不到 bvid=$bvid")
        if (data.bvid.isNullOrEmpty()) {
            throw BilibiliApiException("view 接口返回 code=${payload.code} 找不到 bvid=$bvid")
        }
        if ((data.cid ?: 0) == 0L) {
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
        val parsed = base.toHttpUrlOrNull() ?: throw BilibiliApiException("invalid url: $base")
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
        // 用 suspendCancellableCoroutine 包裹:协程取消(切换清晰度/分P/退出播放页)
        // 时立即 call.cancel(),避免阻塞式 execute() 继续占用 IO 线程与连接。
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val body = it.body.string()
                                if (!it.isSuccessful) {
                                    throw BilibiliApiException("B 站 API ${it.code}: ${body.take(200)}")
                                }
                                if (continuation.isActive) {
                                    continuation.resume(json.decodeFromString<T>(body))
                                }
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                })
            }
        }
    }

    companion object {
        // 无凭证时默认清晰度:80 = 1080P。
        private const val DEFAULT_QUALITY = 80
        // 有凭证(如 SESSDATA)时首选清晰度:127 = 4K,B 站自动降级到视频可用的最高质量。
        private const val PREFERRED_QUALITY_WITH_COOKIE = 127
        private const val WBI_KEY_TTL_MILLIS = 10L * 60 * 1000

        // B 站反爬要求:必须带 PC 端 User-Agent + Referer 才能拿到 playurl。
        // 与 PlaybackService 的 BILI_CDN_HEADERS 同源(单一来源),播放流/playurl 共用。
        private val DIRECT_HEADERS = com.example.mediacenter.player.BILI_CDN_HEADERS
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
            val imgUrl: String? = null,
            @SerialName("sub_url")
            val subUrl: String? = null,
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
        val bvid: String? = null,
        val cid: Long? = null,
        val pages: List<PageData>? = null,
    ) {
        @Serializable
        data class PageData(
            val cid: Long = 0,
            val part: String? = null,
            val duration: Long = 0,
        )
    }
}

@Serializable
private data class BiliPlayUrlResponse(
    val code: Int = 0,
    val data: PlayUrlData? = null,
) {
    @Serializable
    data class PlayUrlData(
        val quality: Int = 0,
        val durl: List<Durl>? = null,
        @SerialName("accept_quality")
        val acceptQuality: List<Int>? = null,
        @SerialName("accept_description")
        val acceptDescription: List<String>? = null,
    ) {
        @Serializable
        data class Durl(
            val url: String? = null,
            val size: Long = 0,
        )
    }
}


