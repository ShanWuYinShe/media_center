package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.GlassFill
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape

/**
 * 弹窗动作按钮:统一项目 TV 焦点态(胶囊 + 白框 + 缩放)。
 *
 * Material3 AlertDialog 默认用 TextButton,焦点只有一层很淡的灰底,
 * 在 10ft 电视上几乎看不出当前选的是"取消"还是"确认";这里用
 * tv-material Button 统一替换。
 *
 * 注意 tv-material 的 Button 内容 Row 是 wrap-content 且按 TopStart 摆放
 * (见 androidx.tv.material3.ButtonImpl / SurfaceImpl):按钮被撑宽后文字会
 * 贴在左侧。所以文字必须自己占满可用宽度并居中;次级按钮用 [GlassFill]
 * 填充,避免在 CardGray 弹窗上"只剩文字看不到按钮"。
 *
 * @param fillWidth 调用方要求按钮横向铺满时传 true(如弹窗内纵向排列的动作列表),
 *   否则保持按内容收缩、最小 96dp。
 */
@Composable
fun TvDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    fillWidth: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .requiredHeight(48.dp)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 96.dp)),
        shape = tvPillButtonShape(),
        border = tvPillButtonBorder(),
        scale = tvButtonFocusScale(),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) AppleBlue else GlassFill,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
            disabledContainerColor = GlassFill,
            disabledContentColor = TextTertiary,
        ),
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 横向铺满时占满按钮宽度;收缩态留出与 96dp 最小宽一致的居中区域。
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 64.dp),
        )
    }
}
