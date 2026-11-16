package com.example.mediacenter.data

/**
 * 播放清晰度默认策略(纯逻辑,可 JVM 单测)。
 *
 * YouTube 服务端按清晰度降序返回全部档位,`options.first()` 即最高档(可达 4K/8K)。
 * 低端盒子的弱 GPU/弱网直接起播最高档往往先卡缓冲、甚至解码掉帧;低端机默认
 * 收敛到 ≤720P,手动切清晰度(selectQuality)完全不受影响。
 *
 * 只重排 options:把首选档提到首位,其余档位保留(供播放页清晰度菜单切换),
 * URL/音轨等字段原样不动。
 */
object PlaybackTuning {
    /** 低端机默认清晰度上限(YouTube qn=height)。 */
    internal const val LOW_END_MAX_QN = 720

    fun preferSmoothInitialOption(result: PlaybackResult, lowEnd: Boolean): PlaybackResult {
        if (!lowEnd) return result
        val options = result.options
        if (options.size < 2) return result
        val activeQn = options.first().qn ?: return result
        if (activeQn <= LOW_END_MAX_QN) return result
        val preferred = options
            .filter { it.qn != null && it.qn <= LOW_END_MAX_QN }
            .maxByOrNull { it.qn ?: 0 }
            ?: return result
        return result.copy(options = listOf(preferred) + options.filter { it.url != preferred.url })
    }
}
