package com.example.mediacenter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.TextPrimary

@Composable
fun TopBar(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    providerLabel: String? = null,
    onProviderClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Media Center", color = TextPrimary, style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 视频源入口:仅当存在多个 provider 时显示
            if (providerLabel != null && onProviderClick != null) {
                TvIconButton(
                    onClick = onProviderClick,
                    contentDescription = providerLabel,
                ) {
                    Text(
                        text = providerLabel,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TvIconButton(
                onClick = onSettingsClick,
                contentDescription = "设置",
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary)
            }
            TvIconButton(
                onClick = onSearchClick,
                contentDescription = "搜索",
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextPrimary)
            }
        }
    }
}

// TvIconButton 是带 TV 焦点反馈的图标按钮:遥控器聚焦时显示高亮背景环,
// 替代纯图标按钮在 10ft 距离下几乎不可见的焦点指示。
@Composable
private fun TvIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .requiredSize(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) AppleBlue.copy(alpha = 0.25f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            ),
    ) {
        content()
    }
}
