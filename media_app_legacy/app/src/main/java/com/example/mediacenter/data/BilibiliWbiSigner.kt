package com.example.mediacenter.data

import java.net.URLEncoder
import java.security.MessageDigest

// B 站 WBI 反爬签名所需的重排表(移植自 BilibiliClient.signWBIParams)。
// 服务于 BilibiliApi:让 App 端拿到 catalog 的 bvid 后自己调 B 站 view/playurl。
private val MIXIN_KEY_ENC_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52,
)

// WBI 签名前要过滤掉这几个字符(B 站反爬策略)。
private val WBI_SANITIZE_REPLACERS = listOf("!" to "", "'" to "", "(" to "", ")" to "", "*" to "")

private const val WBI_MIXIN_KEY_LENGTH = 32

/**
 * 用 [imgKey] + [subKey](从 B 站 nav 接口拿到)对 [params] 做 WBI 签名,
 * 返回包含原参数 + `wts`(时间戳) + `w_rid`(签名) 的完整 query map,
 * 可直接拼到上游 API URL 上。
 *
 * 算法移植自服务端 `BilibiliClient.signWBIParams`(media_server/bilibili.go),
 * 让 App 端在国内可直连源上完全自己解析播放地址,server 端不再代理解析。
 */
fun signWbiParams(
    params: Map<String, String>,
    imgKey: String,
    subKey: String,
): Map<String, String> {
    val mixinKey = getMixinKey(imgKey + subKey)
    val all = LinkedHashMap(params)
    all["wts"] = (System.currentTimeMillis() / 1000L).toString()
    val keys = all.keys.sorted()
    val parts = keys.map { key ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(sanitizeWbiValue(all.getValue(key)), "UTF-8")
    }
    val wRid = md5Hex(parts.joinToString("&") + mixinKey)
    all["w_rid"] = wRid
    return all
}

private fun getMixinKey(orig: String): String {
    val builder = StringBuilder()
    for (index in MIXIN_KEY_ENC_TAB) {
        if (index in orig.indices) builder.append(orig[index])
    }
    val mixed = builder.toString()
    return if (mixed.length > WBI_MIXIN_KEY_LENGTH) mixed.substring(0, WBI_MIXIN_KEY_LENGTH) else mixed
}

private fun sanitizeWbiValue(value: String): String {
    var result = value
    for ((bad, good) in WBI_SANITIZE_REPLACERS) {
        result = result.replace(bad, good)
    }
    return result
}

private fun md5Hex(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append("%02x".format(b.toInt() and 0xff))
    }
    return sb.toString()
}
