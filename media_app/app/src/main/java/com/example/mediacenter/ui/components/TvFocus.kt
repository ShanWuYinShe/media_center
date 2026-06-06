package com.example.mediacenter.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.AppleTvColors

/**
 * 给一个 Modifier 附加 tvOS 风格焦点态(scale + shadow + Apple Blue 抬升 + 边框),
 * 并**就地暴露** `interactionSource` 给调用方,这样 chip / poster 等
 * 组件**直接**用同一个 interactionSource 拿焦点态,不会出现"内外
 * 两个 focused 状态不同步"的问题。
 *
 * 用法:
 *   val interaction = remember { MutableInteractionSource() }
 *   val focused by interaction.collectIsFocusedAsState()
 *   Box(modifier = Modifier.tvFocusable(interactionSource = interaction, onClick = ...))
 */
class TvFocusState internal constructor(val interactionSource: MutableInteractionSource) {
    val focused: Boolean get() = false // 委托给 collectIsFocusedAsState
}

@Composable
fun rememberTvFocusState(): TvFocusState = remember {
    TvFocusState(MutableInteractionSource())
}

fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.12f,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tvFocusScale",
    )

    val elevation by animateDpAsState(
        targetValue = if (focused) 40.dp else 0.dp,
        animationSpec = tween(durationMillis = 250),
        label = "tvFocusElevation",
    )

    val borderWidth by animateDpAsState(
        targetValue = if (focused) 3.dp else 0.5.dp,
        animationSpec = tween(durationMillis = 200),
        label = "tvFocusBorder",
    )

    val borderColor = if (focused) {
        AppleTvColors.GlassBorderFocused
    } else {
        Color.Transparent
    }

    val currentOnFocusChange by rememberUpdatedState(onFocusChange)
    LaunchedEffect(focused) {
        currentOnFocusChange?.invoke(focused)
    }

    this
        .scale(scale)
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black,
            spotColor = AppleTvColors.AppleBlue,
            clip = false,
        )
        .border(width = borderWidth, color = borderColor, shape = shape)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.focusable(enabled = enabled, interactionSource = source)
            }
        )
}

fun Modifier.tvFocusableLite(
    shape: Shape = RoundedCornerShape(999.dp),
    focusedScale: Float = 1.10f,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tvFocusableLiteScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused) 18.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "tvFocusableLiteElevation",
    )

    val currentOnFocusChange by rememberUpdatedState(onFocusChange)
    LaunchedEffect(focused) {
        currentOnFocusChange?.invoke(focused)
    }

    this
        .scale(scale)
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black,
            spotColor = AppleTvColors.AppleBlue,
            clip = false,
        )
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    onClick = onClick,
                )
            } else {
                Modifier.focusable(interactionSource = source)
            }
        )
}

/** tvOS spec highlight:海报顶部 5%~10% 白色渐变,焦点态变亮。 */
@Composable
fun BoxScope.SpecHighlight(shape: Shape, isFocused: Boolean = false) {
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 0.10f else 0.05f,
        animationSpec = tween(durationMillis = 200),
        label = "specHighlightAlpha",
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = alpha),
                    0.5f to Color.Transparent,
                ),
                shape = shape,
            )
    )
}

@Composable
fun rememberInitialFocus(): FocusRequester {
    return remember { FocusRequester() }
}
