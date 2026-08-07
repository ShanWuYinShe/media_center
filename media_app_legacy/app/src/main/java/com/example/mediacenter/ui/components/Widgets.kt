package com.example.mediacenter.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mediacenter.MainActivity
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.views.PillIcon
import com.example.mediacenter.ui.views.PillIconView

internal fun MainActivity.label(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int,
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            // 字号同样按 TV 设计密度换算成像素,与 dp() 布局保持同一比例基准,
            // 避免设备上报 density 异常时文字与布局比例不一致。
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeSp * tvDensity)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setIncludeFontPadding(true)
            setMaxLines(maxLines)
            ellipsize = TextUtils.TruncateAt.END
        }
    }

internal fun MainActivity.glassText(
        text: String,
        sizeSp: Float,
        color: Int,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): TextView {
        return label(text, sizeSp, color, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = true
            isClickable = true
            applyPillFocus(this, false, onFocusChange)
        }
    }

internal fun MainActivity.iconPill(
        text: String,
        icon: PillIcon,
        selected: Boolean,
        heightDp: Int,
        horizontalPaddingDp: Int,
        onClick: () -> Unit,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = PillIconView(this, icon).apply {
            setIconColor(Colors.TEXT_PRIMARY)
        }
        val textView = label(text, 15f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
        row.addView(textView, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(10)))
        applyPillFocus(row, selected, onFocusChange) { color ->
            iconView.setIconColor(color)
            textView.setTextColor(color)
        }
        return row
    }

internal fun MainActivity.circleIconButton(
        icon: PillIcon,
        onClick: () -> Unit,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): FrameLayout {
        val button = FrameLayout(this).apply {
            minimumWidth = dp(54)
            minimumHeight = dp(54)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = PillIconView(this, icon).apply {
            setIconColor(Colors.TEXT_PRIMARY)
        }
        button.addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        fun update(focused: Boolean) {
            button.background = rounded(
                if (focused) Colors.APPLE_BLUE else Colors.GlassFill,
                999,
                if (focused) 3 else 1,
                if (focused) Colors.GLASS_BORDER_FOCUSED else Colors.GlassBorder,
            )
            button.elevationCompat(if (focused) dp(8).toFloat() else 0f)
            button.animate().cancel()
            button.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(140).start()
        }
        update(false)
        button.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
        return button
    }

internal fun MainActivity.primaryPill(
        text: String,
        heightDp: Int,
        horizontalPaddingDp: Int,
        textSizeSp: Float = 16f,
        icon: PillIcon? = null,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = icon?.let {
            PillIconView(this, it).apply { setIconColor(Colors.DEEP_SPACE) }
        }
        if (iconView != null) {
            row.addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        val textView = label(text, textSizeSp, Colors.DEEP_SPACE, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        row.addView(
            textView,
            LinearLayout.LayoutParams(wrap(), wrap()).apply {
                if (iconView != null) leftMargin = dp(10)
            },
        )
        fun update(focused: Boolean) {
            row.background = rounded(
                if (focused) Colors.APPLE_BLUE else Colors.TEXT_PRIMARY,
                999,
                if (focused) 3 else 0,
                if (focused) Colors.GLASS_BORDER_FOCUSED else Color.TRANSPARENT,
            )
            row.elevationCompat(if (focused) dp(10).toFloat() else dp(2).toFloat())
            val contentColor = if (focused) Colors.TEXT_PRIMARY else Colors.DEEP_SPACE
            textView.setTextColor(contentColor)
            iconView?.setIconColor(contentColor)
            row.animate().cancel()
            row.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(140).start()
        }
        update(false)
        row.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
        return row
    }

internal fun MainActivity.pill(
        text: String,
        selected: Boolean,
        heightDp: Int,
        horizontalPaddingDp: Int,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit,
    ): TextView {
        return label(text, 15f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            applyPillFocus(this, selected, onFocusChange)
        }
    }

internal fun MainActivity.applyPillFocus(
        view: TextView,
        selected: Boolean,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ) {
        applyPillFocus(view, selected, onFocusChange) { color ->
            view.setTextColor(color)
        }
    }

internal fun MainActivity.applyPillFocus(
        view: View,
        selected: Boolean,
        onFocusChange: ((Boolean) -> Unit)? = null,
        setContentColor: (Int) -> Unit,
    ) {
        fun update(focused: Boolean) {
            view.background = when {
                focused -> rounded(Colors.APPLE_BLUE, 999, 3, Colors.GLASS_BORDER_FOCUSED)
                selected -> rounded(Colors.GlassFillStrong, 999, 2, Colors.APPLE_BLUE)
                else -> rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            }
            view.elevationCompat(if (focused) dp(8).toFloat() else 0f)
            setContentColor(
                when {
                    focused -> Colors.TEXT_PRIMARY
                    selected -> Colors.APPLE_BLUE
                    else -> Colors.TEXT_SECONDARY
                },
            )
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
        }
        update(false)
        view.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
    }

internal fun MainActivity.applyCardFocus(
        view: View,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ) {
        fun update(focused: Boolean) {
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
        }
        update(false)
        view.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
    }
