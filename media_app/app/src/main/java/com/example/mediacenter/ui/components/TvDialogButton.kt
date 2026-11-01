package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
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
 */
@Composable
fun TvDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.requiredHeight(48.dp).widthIn(min = 96.dp),
        shape = tvPillButtonShape(),
        border = tvPillButtonBorder(),
        scale = tvButtonFocusScale(),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) AppleBlue else CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
            disabledContainerColor = CardGray.copy(alpha = 0.5f),
            disabledContentColor = TextTertiary,
        ),
    ) {
        Text(label, color = if (enabled) TextPrimary else TextTertiary)
    }
}
