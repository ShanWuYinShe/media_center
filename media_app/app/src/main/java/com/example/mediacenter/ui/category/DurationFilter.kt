package com.example.mediacenter.ui.category

/**
 * 分类列表的时长筛选档位。区间**含边界**,与服务端
 * `minDurationSeconds` / `maxDurationSeconds` 语义一致(服务端只做过滤,
 * 不做档位命名)。时长缺失的视频在启用任一档位时不会出现。
 */
enum class DurationFilter(
    val id: String,
    val label: String,
    val minSeconds: Long?,
    val maxSeconds: Long?,
) {
    ALL("all", "全部", null, null),
    SHORT("short", "短片", null, 599),
    MEDIUM("medium", "中片", 600, 2699),
    LONG("long", "长片", 2700, null),
}
