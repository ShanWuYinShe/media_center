package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
 * 页面级 TV 动作按钮(播放/详情/重试等):统一胶囊、焦点外框与缩放。
 * primary 用 Apple Blue;secondary 用玻璃灰。弹窗内更紧凑的按钮用 [TvDialogButton]。
 */
@Composable
fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = tvPillButtonShape(),
        border = tvPillButtonBorder(),
        scale = tvButtonFocusScale(),
        modifier = modifier.requiredHeight(52.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) AppleBlue else CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
            disabledContainerColor = CardGray.copy(alpha = 0.5f),
            disabledContentColor = TextTertiary,
        ),
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextTertiary,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
