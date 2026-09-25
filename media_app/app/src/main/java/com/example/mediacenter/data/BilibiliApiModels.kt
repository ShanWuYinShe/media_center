package com.example.mediacenter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * B 站 API 响应模型(WBI nav / view / playurl)。
 * 从 BilibiliApi.kt 拆分,与主类分离便于维护。
 */
@Serializable
internal data class BiliNavResponse(
    val code: Int = 0,
    val message: String? = null,
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
internal data class BiliViewResponse(
    val code: Int = 0,
    // B 站业务错误的人类可读原因(风控 -352/-412/-403 等),透传给用户界面
    val message: String? = null,
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
internal data class BiliPlayUrlResponse(
    val code: Int = 0,
    val message: String? = null,
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
