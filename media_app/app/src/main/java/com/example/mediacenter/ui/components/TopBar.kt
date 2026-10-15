package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
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
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
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
