package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape

/**
 * 统一的 TV 胶囊控件:
 * - [selected] 非空 → 可选中 chip(分类时长、清晰度、分P、热词),选中用暗蓝;
 * - [selected] 为空 → 纯动作 chip(视频源、查看全部、查看完整简介)。
 *
 * 焦点态始终是 Apple Blue + 近白外框 + 轻微放大;selected 不能强过焦点态。
 */
@Composable
fun TvChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    maxWidth: Dp = 240.dp,
) {
    Button(
        onClick = onClick,
        shape = tvPillButtonShape(),
        border = tvPillButtonBorder(),
        scale = tvButtonFocusScale(),
        // 宽度上限让 maxLines/Ellipsis 真正生效:LazyRow 里无约束时超长分P名会宽出整屏。
        modifier = modifier.requiredHeight(48.dp).widthIn(max = maxWidth),
        colors = ButtonDefaults.colors(
            containerColor = if (selected == true) AppleBlueDark else CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(
                text = label,
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}
