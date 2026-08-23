package com.example.mediacenter.ui.player

import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary

@Composable
internal fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.requiredHeight(48.dp),
        colors = ButtonDefaults.colors(
            // selected 非聚焦时用暗蓝：焦点态(Apple Blue)必须是视觉最强状态，
            // selected 与 focused 同色会让用户分不清“当前选中”和“现在操作的是哪个”。
            containerColor = if (selected) AppleBlueDark else CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
        ),
    ) {
        Text(label, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
    }
}
