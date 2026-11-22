package com.example.mediacenter.ui.detail

import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.PlaybackTuning
import com.example.mediacenter.data.QualityOption

/**
 * 详情页清晰度/选集展示口径(纯逻辑,JVM 单测覆盖)。
 *
 * 详情页在解析播放地址的同一份结果上展示"清晰度"与"选集":
 * - 清晰度:优先用 playurl/服务端返回的 availableQualities(全部可切换档);
 *   B 站旧响应只有单档、或服务端只回单档时退回 options,且 ≤1 档不显示切换行。
 * - 当前档:与播放页同一口径做自动选档收敛(preferInitialOption),保证详情页
 *   高亮/按钮文案与实际起播档位一致(否则弱机会显示 4K 但实际播 720P)。
 * - 选集:多 P 时按续播页与解析结果页取较大值,并夹到合法区间(目录里的
 *   _pN 可能指向已删除的分页)。
 */
internal data class DetailPlaybackUi(
    val currentQualityQn: Int?,
    val currentQualityLabel: String?,
    val qualityOptions: List<QualityOption>,
    val totalPages: Int,
    val pageTitles: List<String>,
    val pageIndex: Int,
)

internal fun detailPlaybackUi(
    result: PlaybackResult,
    maxInitialQualityQn: Int,
    resumePage: Int,
): DetailPlaybackUi {
    val tuned = PlaybackTuning.preferInitialOption(result, maxInitialQualityQn)
    val active = tuned.options.firstOrNull()
    val qualityOptions = detailQualityOptions(result)
    val currentQn = active?.qn
    val currentLabel = qualityOptions.firstOrNull { it.qn == currentQn }?.label
        ?: active?.label?.takeIf { it.isNotBlank() }
    val pages = result.pages
    val totalPages = pages.totalPages
    val pageIndex = if (totalPages > 1) {
        maxOf(resumePage, pages.currentPage).coerceIn(0, totalPages - 1)
    } else {
        0
    }
    return DetailPlaybackUi(
        currentQualityQn = currentQn,
        currentQualityLabel = currentLabel,
        qualityOptions = qualityOptions,
        totalPages = totalPages,
        pageTitles = pages.pageTitles,
        pageIndex = pageIndex,
    )
}

/** 可切换的清晰度档位;不足 2 档时返回空列表(没有可选项就不渲染"清晰度"行)。 */
internal fun detailQualityOptions(result: PlaybackResult): List<QualityOption> {
    if (result.availableQualities.size > 1) return result.availableQualities
    val fromOptions = result.options
        .mapNotNull { option ->
            option.qn.takeIf { it > 0 }?.let { QualityOption(it, option.label) }
        }
        .distinctBy { it.qn }
    return if (fromOptions.size > 1) fromOptions else emptyList()
}
