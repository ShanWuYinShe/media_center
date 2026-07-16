package com.example.mediacenter.ui.theme

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
            content = content,
        )
    }
}
