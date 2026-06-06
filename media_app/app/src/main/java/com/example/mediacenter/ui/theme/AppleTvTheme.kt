package com.example.mediacenter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple TV (tvOS) 风格主题。
 *
 * 设计取舍:
 * - OLED 深空黑底,避免纯白文本与亮色背景的"灯泡"效应
 * - 唯一强调色 Apple Blue (#0A84FF) 用于焦点 / 主操作,其他元素用灰阶
 * - 多层级文本灰:Primary 几乎纯白,Secondary 浅灰 70%,Tertiary 中灰 55%
 * - 玻璃质感由半透明白 + 边框 1dp 模拟(Compose 没有真正的 backdrop-filter)
 * - 圆角统一 18-24dp,海报卡稍小 14dp 以保留电影海报的纵向气质
 */
object AppleTvColors {
    // 背景层(OLED 黑 → 深灰渐变)
    val DeepSpace = Color(0xFF000000)
    val SurfaceBase = Color(0xFF050507)
    val SurfaceElevated = Color(0xFF0E1014)
    val GlassFill = Color(0x14FFFFFF)          // 8% 白,玻璃卡
    val GlassFillStrong = Color(0x1FFFFFFF)    // 12% 白,焦点态加强
    val GlassBorder = Color(0x1AFFFFFF)        // 10% 白边框
    val GlassBorderFocused = Color(0xFFE5E5E7) // 焦点时近白色边框,模拟 tvOS halo 内圈

    // 文本灰阶
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFB0B3B8)
    val TextTertiary = Color(0xFF86868B)
    val TextDanger = Color(0xFFFF453A)         // tvOS 系统红
    val TextSuccess = Color(0xFF32D74B)        // tvOS 系统绿

    // 强调色(Apple Blue / 焦点蓝)
    val AppleBlue = Color(0xFF0A84FF)
    val AppleBlueDim = Color(0x800A84FF)       // 50% 透明,用于光晕
    val AppleBlueGlow = Color(0x4D0A84FF)      // 30% 透明,外发光最外层
    val AppleBluePressed = Color(0xFF0061CC)

    // 顶部装饰渐变(从微微 indigo → 透明,模拟 Apple TV+ 顶栏)
    val HeroGradientTop = Color(0xFF111324)
    val HeroGradientBottom = Color(0xFF000000)
}

object AppleTvShapes {
    val Poster = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(20.dp)
    val Chip = RoundedCornerShape(999.dp)       // 完全胶囊
    val Button = RoundedCornerShape(999.dp)
    val Dialog = RoundedCornerShape(24.dp)
}

/**
 * 字体使用系统 sans-serif(Android 11+ 是 Roboto / 部分定制 ROM 提供更接近 SF Pro 的字体)。
 * Apple TV 视觉关键不是字面"SF Pro",而是负字距 + SemiBold 的大字号 + 浅灰副标题。
 */
private val AppleTvTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1.0).sp,
        color = AppleTvColors.TextPrimary,
    ),
    headlineLarge = TextStyle(
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp,
        color = AppleTvColors.TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        color = AppleTvColors.TextPrimary,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        color = AppleTvColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
        color = AppleTvColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        color = AppleTvColors.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = AppleTvColors.TextSecondary,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = AppleTvColors.TextSecondary,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = AppleTvColors.TextTertiary,
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
        color = AppleTvColors.TextPrimary,
    ),
)

private val AppleTvDarkColors = darkColorScheme(
    primary = AppleTvColors.AppleBlue,
    onPrimary = AppleTvColors.TextPrimary,
    secondary = AppleTvColors.AppleBlueDim,
    background = AppleTvColors.DeepSpace,
    onBackground = AppleTvColors.TextPrimary,
    surface = AppleTvColors.SurfaceBase,
    onSurface = AppleTvColors.TextPrimary,
    surfaceVariant = AppleTvColors.SurfaceElevated,
    onSurfaceVariant = AppleTvColors.TextSecondary,
    error = AppleTvColors.TextDanger,
)

private val AppleTvShapeSet = Shapes(
    small = AppleTvShapes.Poster,
    medium = AppleTvShapes.Card,
    large = AppleTvShapes.Dialog,
)

@Composable
fun AppleTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppleTvDarkColors,
        typography = AppleTvTypography,
        shapes = AppleTvShapeSet,
        content = content,
    )
}
