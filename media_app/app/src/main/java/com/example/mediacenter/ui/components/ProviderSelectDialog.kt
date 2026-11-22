package com.example.mediacenter.ui.components
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.GlassFill
import com.example.mediacenter.ui.theme.TextPrimary

// 视频源切换弹层:仅当 server 返回多个 provider 时由首页展示。
@Composable
fun ProviderSelectDialog(
    providers: List<ProviderInfo>,
    currentProvider: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 进入弹窗即聚焦当前源(TV:对齐"当前选中项",按 OK 不会误切到别的源)
    val currentFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }
    val currentEntryIndex = providers.indexOfFirst { it.id == currentProvider }
        .let { if (it >= 0) it else 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择视频源", color = TextPrimary) },
        text = {
            // 正文可滚动:provider 增多超出弹层高度时不至于溢出/裁切
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providers.forEachIndexed { index, provider ->
                    val isCurrent = provider.id == currentProvider
                    val isLast = index == providers.lastIndex
                    Button(
                        shape = tvPillButtonShape(),
                        border = tvPillButtonBorder(),
                        scale = tvButtonFocusScale(),
                        onClick = { onSelect(provider.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(48.dp)
                            .then(
                                if (index == currentEntryIndex) Modifier.focusRequester(currentFocus) else Modifier,
                            )
                            .then(
                                if (isLast) Modifier.focusProperties { down = cancelFocus } else Modifier,
                            ),
                        colors = ButtonDefaults.colors(
                            // 当前源非聚焦时用暗蓝 + 对勾图标；亮蓝只留给焦点态。
                            // 非当前源用 GlassFill:CardGray 在 CardGray 弹窗上不可见,
                            // 旧实现只剩一行文字浮着,看着像"没对齐的裸文本"。
                            containerColor = if (isCurrent) AppleBlueDark else GlassFill,
                            focusedContainerColor = AppleBlue,
                            contentColor = TextPrimary,
                            focusedContentColor = TextPrimary,
                        ),
                    ) {
                        // 选中态加对勾图标:仅靠底色区分在 TV 亮度/色偏下不易分辨。
                        // 用自绘矢量图标,不用 Unicode "✓" 拼进文本(字体 fallback 风格不一)。
                        // 未选中行也保留同宽的空位,让所有行的视频源名称左边缘对齐
                        // (旧实现选中行多一个图标,文字整体右移,几行看起来错位)。
                        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = provider.name ?: provider.id,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // tv-material Button 内容 Row 不会横向撑满(见 TvDialogButton 注释),
                            // 这里显式铺满剩余宽度,名称才能全部从同一左边缘开始。
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TvDialogButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(cancelFocus)
                    .focusProperties {
                        up = currentFocus
                    },
            )
        },
        containerColor = CardGray,
    )
}
