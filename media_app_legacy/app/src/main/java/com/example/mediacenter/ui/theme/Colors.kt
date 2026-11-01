package com.example.mediacenter.ui.theme

import android.graphics.Color


object Colors {
    // 与 media_app 的 TV 设计 tokens 对齐:OLED 深色底、玻璃灰卡片、
    // Apple Blue 唯一强调色、三级灰阶文字。legacy 仍用原生 View 实现,
    // 但视觉口径与新版 Compose 保持一致。
    const val DEEP_SPACE: Int = 0xFF0B0B0B.toInt()
    const val BACKGROUND_TOP: Int = 0xFF141414.toInt()
    const val BACKGROUND_MID: Int = 0xFF111111.toInt()
    const val SURFACE_ELEVATED: Int = 0xFF1C1C1E.toInt()
    const val SURFACE_FOCUSED: Int = 0xFF242426.toInt()
    const val GLASS_BORDER_FOCUSED: Int = 0xFFE5E5EA.toInt()
    const val TEXT_PRIMARY: Int = 0xFFF5F5F7.toInt()
    const val TEXT_SECONDARY: Int = 0xFFAEAEB2.toInt()
    const val TEXT_TERTIARY: Int = 0xFF8E8E93.toInt()
    const val TEXT_DANGER: Int = 0xFFFF453A.toInt()
    const val APPLE_BLUE: Int = 0xFF007AFF.toInt()
    val AccentGlow: Int = Color.argb(40, 0, 122, 255)
    val GlassFill: Int = Color.argb(20, 255, 255, 255)
    val GlassFillStrong: Int = Color.argb(31, 255, 255, 255)
    val GlassBorder: Int = Color.argb(32, 255, 255, 255)
    val PlaceholderTop: Int = Color.argb(255, 28, 32, 44)
    val PlaceholderBottom: Int = Color.argb(255, 12, 14, 20)
}
