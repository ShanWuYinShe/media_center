package com.example.mediacenter.ui.components

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.Colors
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * MainActivity 的纯 UI 工具函数(extension)。
 * 全部无状态(除 tvDensity/pendingTextUpdates 外),拆分自原 3999 行 MainActivity。
 */

internal fun MainActivity.appBackground(): Drawable {
    val base = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Colors.BACKGROUND_TOP, Colors.BACKGROUND_MID, Colors.DEEP_SPACE),
    )
    // 顶部中央叠一层极淡的 Apple-Blue 径向柔光,增加纵深而不引入新色相。
    val glow = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Colors.AccentGlow, Color.TRANSPARENT),
    ).apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(560).toFloat()
        setGradientCenter(0.5f, 0f)
    }
    return LayerDrawable(arrayOf(base, glow))
}

internal fun MainActivity.detailBackdropScrim(): View {
    val scrim = FrameLayout(this)
    scrim.addView(
        View(this).apply { setBackgroundColor(Color.argb(118, 5, 7, 13)) },
        frameMatch(),
    )
    scrim.addView(
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 5, 7, 13)),
            )
        },
        frameMatch(),
    )
    scrim.addView(
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(232, 0, 0, 0), Color.TRANSPARENT),
            )
        },
        frameMatch(),
    )
    return scrim
}

internal fun MainActivity.rounded(
    color: Int,
    radiusDp: Int,
    strokeDp: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        // 描边宽度按 2 倍绘制:GradientDrawable 的 stroke 以 view 边界为中心线,
        // 外侧一半会被 view 裁掉,直接按设计值画只可见一半(1dp 边框只剩 0.5dp,
        // 2dp 焦点框只剩 1dp),看起来像"边框被遮挡了一部分"。2 倍后可见厚度
        // 正好等于调用方写的设计值,全 App 描边一致。
        if (strokeDp > 0) setStroke(dp(strokeDp) * 2, strokeColor)
    }
}

/**
 * 指定「可见厚度」(dp,支持 0.5dp 步进)的圆角描边背景。
 *
 * 与 [rounded] 同一套 2 倍补偿原理(补偿 GradientDrawable 中位线描边被 view
 * 边界裁掉的外侧一半),用于焦点框这类对厚度敏感的细节:3dp 全可见描边在 10ft
 * 电视上偏粗、像"白块",统一改成 1.5dp 细描边 + 底色/缩放/阴影表达焦点。
 */
internal fun MainActivity.roundedVisibleStroke(
    color: Int,
    radiusDp: Int,
    visibleStrokeDp: Float,
    strokeColor: Int,
): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        val strokePx = (visibleStrokeDp * tvDensity * 2f + 0.5f).toInt()
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }
}

internal fun MainActivity.roundedGradient(
    topColor: Int,
    bottomColor: Int,
    radiusDp: Int,
    strokeDp: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
): GradientDrawable {
    return GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(topColor, bottomColor),
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        // 与 rounded() 相同:2 倍描边补偿中位线绘制导致的外侧裁掉一半。
        if (strokeDp > 0) setStroke(dp(strokeDp) * 2, strokeColor)
    }
}

internal fun View.elevationCompat(value: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        elevation = value
    }
}

internal fun TextView.letterSpacingCompat(value: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        letterSpacing = value
    }
}

internal fun videoMeta(video: VideoSummary): String {
    return listOfNotNull(
        video.durationSeconds?.let { formatDurationSeconds(it) },
    ).joinToString(" · ").ifBlank { "公开视频" }
}

internal fun formatDurationSeconds(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val sec = total % 60
    val min = (total / 60) % 60
    val hr = total / 3600
    return if (hr > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hr, min, sec)
    } else {
        String.format(Locale.ROOT, "%d:%02d", min, sec)
    }
}

internal fun formatTime(ms: Long): String {
    return formatDurationSeconds(ms / 1000)
}

internal fun MainActivity.gridColumns(): Int {
    // TV 首页固定 3 列大卡片：10ft 观看距离下 4 列以上文字会明显变小，
    // 老人更难读；与新版 Compose 首页保持一致。
    return 3
}

internal fun MainActivity.posterCardWidth(columns: Int): Int {
    val available = resources.displayMetrics.widthPixels - dp(96) - dp(16 * columns)
    return max(dp(116), available / columns)
}

internal fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
internal fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun MainActivity.frameMatch(): FrameLayout.LayoutParams {
    return FrameLayout.LayoutParams(match(), match())
}

internal fun MainActivity.linearMatch(): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(match(), wrap())
}

internal fun MainActivity.linearWrap(): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(wrap(), wrap())
}

internal fun LinearLayout.LayoutParams.withStartMargin(value: Int): LinearLayout.LayoutParams {
    leftMargin = value
    return this
}

internal fun LinearLayout.LayoutParams.withEndMargin(value: Int): LinearLayout.LayoutParams {
    rightMargin = value
    return this
}

internal fun LinearLayout.LayoutParams.withTopMargin(value: Int): LinearLayout.LayoutParams {
    topMargin = value
    return this
}

// TV 设计密度:以 Android TV 标准设计画布 960×540dp(1080p=xhdpi)为基准,
// 用真实像素尺寸反推有效密度,使布局尺寸与字号只取决于真实分辨率比例,
// 不受设备上报 density 影响。在正确上报的 1080p 设备上结果等于系统 density(2.0),
// 因此对规范设备零副作用;在误报 density 的电视/盒子上则自动校正,
// 让全部页面在 720p/1080p/4K 上保持一致的 10ft 视觉比例与可读性。
// (定义在 MainActivity 作为成员,见 MainActivity.tvDensity)

internal fun MainActivity.dp(value: Int): Int {
    return (value * tvDensity + 0.5f).toInt()
}
