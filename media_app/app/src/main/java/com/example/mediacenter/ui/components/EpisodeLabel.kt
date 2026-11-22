package com.example.mediacenter.ui.components

/**
 * 分P(选集)标题展示规则。
 *
 * 部分片源(尤其 B 站多P)的分P标题是「剧名-00.04.00.000-00.09.30.000-seg01」这类
 * 时间段串:直接当按钮/行文案会在单行省略后只剩无意义的数字片段,用户根本不知道
 * 这是第几集。选集是低频精准操作,集数才是主信息:
 * - 空标题、或含时间区间串 → 只显示「第 N 集」;
 * - 可读标题 → 「第 N 集 · 原标题」,保留集数同时保留语义(如「第 2 集 · 预告」)。
 */
private val TIME_RANGE_PATTERN = Regex("""\d{1,2}[.:]\d{2}[.:]\d{2}[.:]\d{3}""")

internal fun episodeDisplayTitle(index: Int, rawTitle: String?): String {
    val number = "第 ${index + 1} 集"
    val title = rawTitle?.trim().orEmpty()
    if (title.isEmpty() || TIME_RANGE_PATTERN.containsMatchIn(title)) return number
    return "$number · $title"
}
