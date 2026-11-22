package com.example.mediacenter.ui

/**
 * 分P(选集)标题展示规则(与 normal 端同口径)。
 *
 * 部分片源(尤其 B 站多P)的分P标题是「剧名-00.04.00.000-00.09.30.000-seg01」这类
 * 时间段串:直接当行文案会只剩无意义的数字片段,用户根本不知道是第几集。
 * - 空标题、或含时间区间串 → 只显示「第 N 集」;
 * - 可读标题 → 「第 N 集 · 原标题」。
 */
private val TIME_RANGE_PATTERN = Regex("""\d{1,2}[.:]\d{2}[.:]\d{2}[.:]\d{3}""")

internal fun episodeDisplayTitle(index: Int, rawTitle: String?): String {
    val number = "第 ${index + 1} 集"
    val title = rawTitle?.trim().orEmpty()
    if (title.isEmpty() || TIME_RANGE_PATTERN.containsMatchIn(title)) return number
    return number + " · " + title
}
