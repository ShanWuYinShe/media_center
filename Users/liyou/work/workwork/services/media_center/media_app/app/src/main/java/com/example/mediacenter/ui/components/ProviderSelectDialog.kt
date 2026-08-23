package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.UiIcons
import com.example.mediacenter.ui.theme.TextSecondary

// 视频源切换弹层:仅当 server 返回多个 provider 时由首页展示。
@Composable
fun ProviderSelectDialog(
    providers: List<ProviderInfo>,
    currentProvider: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择视频源", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    Button(
                        onClick = { onSelect(provider.id) },
                        modifier = Modifier.fillMaxWidth().requiredHeight(48.dp),
                        colors = ButtonDefaults.colors(
                            // 当前源非聚焦时用暗蓝 + 对勾图标；亮蓝只留给焦点态
                            containerColor = if (provider.id == currentProvider) AppleBlueDark else CardGray,
                            focusedContainerColor = AppleBlue,
                            contentColor = TextPrimary,
                            focusedContentColor = TextPrimary,
                        ),
                    ) {
                        // 选中态加对勾图标:仅靠底色区分在 TV 亮度/色偏下不易分辨。
                        // 用自绘矢量图标,不用 Unicode "✓" 拼进文本(字体 fallback 风格不一)。
                        if (provider.id == currentProvider) {
                            Icon(
                                imageVector = UiIcons.Check,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = provider.name ?: provider.id,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = CardGray,
    )
}
