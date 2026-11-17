package com.example.mediacenter.data

import com.example.mediacenter.util.DevicePerformance

/**
 * 播放清晰度默认策略(纯逻辑,可 JVM 单测)。
 *
 * YouTube 服务端按清晰度降序返回全部档位,`options.first()` 即最高档(可达 4K/8K)。
 * 但播放页只消费其中一档:1080p 电视拉 4K 流是纯浪费(带宽、解码、起播都更慢),
 * 低端盒子更明显。因此自动选档时按「设备档位 + 屏幕物理高度」收敛默认清晰度,
 * 手动切清晰度(selectQuality)完全不受影响。
 *
 * 只重排 options:把首选档提到首位,其余档位保留(供播放页清晰度菜单切换),
 * URL/音轨等字段原样不动。
 */
object PlaybackTuning {
    /** 低端机默认清晰度上限(YouTube qn=height)。 */
    internal const val LOW_END_MAX_QN = 720

    /**
     * 自动选档上限(纯逻辑):设备档位与屏幕物理高度取小;低端机再压到 720P。
     * 播放页与详情页预取用同一口径,保证预取的媒体地址与播放页实际首档一致
     * (否则预取缓存命中不了)。
     */
    fun maxInitialQualityQn(displayHeightPx: Int): Int =
        if (DevicePerformance.isLowEnd) {
            minOf(displayHeightPx, LOW_END_MAX_QN)
        } else {
            displayHeightPx
        }

    /**
     * 自动选档收敛:当前最高档高于 [maxQn] 时,把 ≤[maxQn] 的最高档提到首位。
     * [maxQn] 由 [com.example.mediacenter.ui.player.PlayerScreen] 按设备档位与
     * 屏幕物理高度计算;<=0 视为未知,不做调整。
     */
    fun preferInitialOption(result: PlaybackResult, maxQn: Int): PlaybackResult {
        if (maxQn <= 0) return result
        val options = result.options
        if (options.size < 2) return result
        val activeQn = options.first().qn ?: return result
        if (activeQn <= maxQn) return result
        val preferred = options
            .filter { it.qn != null && it.qn <= maxQn }
            .maxByOrNull { it.qn ?: 0 }
            ?: return result
        return result.copy(options = listOf(preferred) + options.filter { it.url != preferred.url })
    }
}
