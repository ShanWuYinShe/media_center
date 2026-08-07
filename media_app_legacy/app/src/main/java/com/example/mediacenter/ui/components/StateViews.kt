package com.example.mediacenter.ui.components

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.mediacenter.MainActivity
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.roundedGradient
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.views.WarningGlyphView

internal fun MainActivity.centeredLoading(text: String): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = rounded(Colors.GlassFillStrong, 28, 1, Colors.GlassBorder)
        }
        card.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(label(text, 16f, Colors.TEXT_SECONDARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(14)))
        return centeredCard(card)
    }

internal fun MainActivity.loadingScrim(): View {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = rounded(Color.argb(184, 0, 0, 0), 999, 1, Colors.GlassBorder)
        }
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        box.addView(label("正在加载…", 15f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        return box
    }

internal fun MainActivity.errorState(message: String, onRetry: () -> Unit, autoRetrying: Boolean = false): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(40), dp(44), dp(36))
            background = roundedGradient(Colors.SURFACE_FOCUSED, Colors.SURFACE_ELEVATED, 30, 1, Colors.GlassBorder)
            minimumWidth = dp(360)
            elevationCompat(dp(18).toFloat())
        }
        val iconWrap = FrameLayout(this).apply {
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
        }
        iconWrap.addView(WarningGlyphView(this), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(iconWrap, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(
            label("加载失败", 20f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(16)),
        )
        card.addView(
            label(message, 15f, Colors.TEXT_SECONDARY, maxLines = 3).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(320)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(8)),
        )
        if (autoRetrying) {
            // 自动重试中:展示进度而非手动按钮,且不抢占焦点——让焦点留在
            // 分类栏(占位 chip),遥控器左键连按 5 次仍可调出系统诊断弹窗。
            val retrying = pill(
                text = "自动重试中…",
                selected = false,
                heightDp = 50,
                horizontalPaddingDp = 40,
                onClick = {},
            ).apply {
                isFocusable = false
                isClickable = false
            }
            card.addView(retrying, LinearLayout.LayoutParams(wrap(), dp(50)).withTopMargin(dp(24)))
        } else {
            val retry = primaryPill("重试", heightDp = 50, horizontalPaddingDp = 40, onClick = onRetry)
            card.addView(retry, LinearLayout.LayoutParams(wrap(), dp(50)).withTopMargin(dp(24)))
            retry.post { retry.requestFocus() }
        }
        return centeredCard(card)
    }

internal fun MainActivity.errorInline(message: String, onRetry: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(label(message, 14f, Colors.TEXT_DANGER, maxLines = 2), LinearLayout.LayoutParams(0, wrap(), 1f))
        row.addView(pill("重试", selected = false, heightDp = 38, horizontalPaddingDp = 18, onClick = onRetry), LinearLayout.LayoutParams(wrap(), dp(38)).withStartMargin(dp(12)))
        return row
    }

internal fun MainActivity.homeInlineError(message: String): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(Color.argb(210, 14, 16, 20), 22, 1, Colors.GlassBorder)
            addView(label(message, 14f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2), LinearLayout.LayoutParams(match(), wrap()))
        }
    }

internal fun MainActivity.emptyState(message: String): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(26), dp(34), dp(26))
            background = rounded(Colors.GlassFill, 28, 1, Colors.GlassBorder)
        }
        card.addView(label(message, 18f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1), linearWrap())
        card.addView(label("换个分类或关键词试试", 13f, Colors.TEXT_TERTIARY, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(8)))
        return centeredCard(card)
    }

internal fun MainActivity.centeredCard(card: View): FrameLayout {
        return FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }
    }
