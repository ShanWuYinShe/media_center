package com.example.mediacenter.ui.components

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
        val textView = label(text, 17f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
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
        contentDescription: String? = null,
    ): FrameLayout {
        val button = FrameLayout(this).apply {
            minimumWidth = dp(54)
            minimumHeight = dp(54)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            // 纯图标按钮必须给 TalkBack 一个可朗读的名称
            if (contentDescription != null) this.contentDescription = contentDescription
        }
        val iconView = PillIconView(this, icon).apply {
            setIconColor(Colors.TEXT_PRIMARY)
        }
        button.addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        val focusedBackground = focusedPillBackground()
        fun update(focused: Boolean) {
            // 焦点描边走 focusedPillBackground():stroke 不再被 view 边界裁掉一半。
            button.background = if (focused) {
                focusedBackground
            } else {
                rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            }
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
        val focusedBackground = focusedPillBackground()
        fun update(focused: Boolean) {
            // 与新版一致:主操作常态即 Apple Blue,焦点只增加近白外框/缩放,
            // 不再用旧版“白底黑字”主按钮。焦点描边走 focusedPillBackground(),
            // 避免 stroke 画在 view 边界上被裁掉一半(见该函数注释)。
            row.background = if (focused) {
                focusedBackground
            } else {
                rounded(Colors.APPLE_BLUE, 999, 0, Color.TRANSPARENT)
            }
            row.elevationCompat(if (focused) dp(10).toFloat() else dp(2).toFloat())
            val contentColor = Colors.TEXT_PRIMARY
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
        return label(text, 17f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
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

    // 三态背景按 view 记忆(WeakHashMap 不阻止回收):遥控器快速划过几十个 chip 时,
    // 每次焦点切换不再新建 GradientDrawable——弱核 API19 盒子上这正是 GC 卡顿点。
    private val pillFocusDrawables = java.util.WeakHashMap<View, Array<android.graphics.drawable.Drawable>>()

    /**
     * 焦点态胶囊背景:Apple Blue 填充 + 1.5dp 浅色焦点描边。
     *
     * 描边只是"焦点在哪"的提示,10ft 距离下 3dp 全可见描边显得又粗又重(像贴了
     * 一圈白胶布),1.5dp 细描边 + 填充色变化 + 1.05~1.08 倍放大 + elevation
     * 阴影已经足够表达焦点,视觉也更干净;roundedVisibleStroke 保证 1.5dp 是
     * 真实可见厚度(不是被裁一半后的 0.75dp)。
     */
    private fun MainActivity.focusedPillBackground(): android.graphics.drawable.Drawable {
        return roundedVisibleStroke(Colors.APPLE_BLUE, 999, 1.5f, Colors.GLASS_BORDER_FOCUSED)
    }

internal fun MainActivity.applyPillFocus(
        view: View,
        selected: Boolean,
        onFocusChange: ((Boolean) -> Unit)? = null,
        setContentColor: (Int) -> Unit,
    ) {
        val states = pillFocusDrawables[view] ?: arrayOf(
            rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder),
            rounded(Colors.GlassFillStrong, 999, 2, Colors.APPLE_BLUE),
            focusedPillBackground(),
        ).also { pillFocusDrawables[view] = it }
        fun update(focused: Boolean) {
            view.background = when {
                focused -> states[2]
                selected -> states[1]
                else -> states[0]
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

/**
 * 横向可滚动的「焦点条」(分类 chip / 清晰度 chip / 热词等共用)。
 *
 * 滚动容器**必须保持 clipChildren=true**(否则滚动时内容会画出视口、盖住其它
 * 元素);用 padding + clipToPadding=false 给子项 1.06 倍焦点放大留出溢出空间,
 * 避免首/末项焦点框被视口裁掉。padH 需大于最大 chip 宽度的 3%(约 4~6dp)。
 */
internal fun MainActivity.horizontalFocusStrip(
        content: View,
        padH: Int = 6,
        padV: Int = 4,
    ): android.widget.HorizontalScrollView {
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = false
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            addView(content)
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
