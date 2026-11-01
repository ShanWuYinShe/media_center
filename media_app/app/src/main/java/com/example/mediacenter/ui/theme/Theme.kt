package com.example.mediacenter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * TV 专用排版:Material 默认字号是移动端尺度,10ft 观看距离下标题层级偏小、
 * 小字(13~14sp)在 1080p 电视上难以辨认。整体上调并加强标题字重,同时保持
 * Primary/Secondary/Tertiary 三级灰阶的层次差。
 */
private val TvTypography = Typography(
    headlineLarge = TextStyle(fontSize = 42.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
)

@Immutable
data class AppleTvColors(
    val background: Color = BackgroundDark,
    val card: Color = CardGray,
    val cardLight: Color = CardGrayLight,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val textTertiary: Color = TextTertiary,
    val accent: Color = AppleBlue,
    val accentDark: Color = AppleBlueDark,
    val focusBorder: Color = FocusBorder,
    val divider: Color = DividerGray,
    val error: Color = ErrorRed,
)

val LocalAppleTvColors = staticCompositionLocalOf { AppleTvColors() }

@Composable
fun MediaCenterTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppleTvColors provides AppleTvColors()) {
        androidx.compose.material3.MaterialTheme(
            // 显式指定深色 colorScheme:不传时默认 lightColorScheme,AlertDialog
            // 默认按钮色、OutlinedButton 边框/文字等系统组件会渲染成浅色主题,
            // 与全局深色视觉冲突。
            typography = TvTypography,
            colorScheme = darkColorScheme(
                primary = AppleBlue,
                onPrimary = Color.White,
                secondary = AppleBlue,
                background = BackgroundDark,
                surface = CardGray,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                error = ErrorRed,
                onError = Color.White,
            ),
            content = content,
        )
    }
}
