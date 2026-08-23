package com.example.mediacenter.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.example.mediacenter.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * 弹窗里的长文本(完整简介/应用日志/更新说明)：可滚动 + 可被遥控器滚动。
 * 普通 verticalScroll(Text) 对 D-pad 无效——文本内部没有可聚焦节点，
 * 遥控器永远滚不动，超出首屏的内容在 TV 上等于不可读。
 *
 * 本组件把容器自身设为 focusable，聚焦时上/下键步进滚动；
 * onPreviewKeyEvent 放在 focusable 之前(祖先位置)才能在隧穿阶段收到按键。
 */
@Composable
fun TvScrollableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxHeight: Dp? = null,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val heightModifier = if (maxHeight != null) Modifier.requiredHeightIn(max = maxHeight) else Modifier
    Text(
        text = text,
        color = color,
        style = style,
        modifier = modifier
            .then(heightModifier)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 滚动到边界时放行按键：否则焦点被困在文本里，
                // 无法继续向下/向上走出正文回到弹窗按钮。
                when (e.key) {
                    Key.DirectionDown -> if (scrollState.canScrollForward) {
                        scope.launch { scrollState.animateScrollBy(160f) }
                        true
                    } else false
                    Key.DirectionUp -> if (scrollState.canScrollBackward) {
                        scope.launch { scrollState.animateScrollBy(-160f) }
                        true
                    } else false
                    else -> false
                }
            }
            .verticalScroll(scrollState)
            .focusable(),
    )
}
