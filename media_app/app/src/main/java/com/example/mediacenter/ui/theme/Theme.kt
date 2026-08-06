package com.example.mediacenter.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
