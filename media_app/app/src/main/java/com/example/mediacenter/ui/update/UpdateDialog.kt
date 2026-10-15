package com.example.mediacenter.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.ui.components.TvScrollableText
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary

// 更新弹窗的三阶段状态:发现新版本 → 下载中(带进度) → 下载失败(可重试)。
sealed interface UpdateDialogState {
    data class Available(val info: AppUpdateInfo) : UpdateDialogState
    data class Downloading(val info: AppUpdateInfo, val progress: Float) : UpdateDialogState
    data class Failed(val info: AppUpdateInfo, val message: String) : UpdateDialogState
}

@Composable
fun UpdateDialog(
    state: UpdateDialogState,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    // 下载是否在对话框之外继续(首页 ViewModel 持有下载状态)。允许时下载中
    // 也能关闭弹窗(“后台下载”)，慢网络下不再是无法逃离的死胡同;
    // 设置页的下载协程随页面存活，关闭即断，保持原阻塞行为。
    allowDismissDuringDownload: Boolean = false,
) {
    // 主按钮焦点路径:弹窗出现或状态切换时把遥控器焦点明确放到主操作上，
    // 不依赖空间猜测(TV 焦点态约定);下载中主按钮禁用，不抢焦点。
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(state) {
        if (state !is UpdateDialogState.Downloading) {
            primaryFocus.requestFocus()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (state !is UpdateDialogState.Downloading || allowDismissDuringDownload) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is UpdateDialogState.Available -> "发现新版本 ${state.info.versionName}"
                    is UpdateDialogState.Downloading -> "正在下载 ${state.info.versionName}…"
                    is UpdateDialogState.Failed -> "下载更新失败"
                },
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateDialogState.Available -> {
                        Text(
                            text = "新版本可用，安装包大小 ${formatMegabytes(state.info.sizeBytes)}。",
                            color = TextSecondary,
                        )
                        state.info.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                            // 更新说明先限高滚动展示，不在弹窗内无限展开；
                            // 遥控器聚焦后上/下键可滚动(普通 verticalScroll 对 D-pad 无效)。
                            TvScrollableText(
                                text = notes,
                                color = TextSecondary,
                                modifier = Modifier.fillMaxWidth(),
                                maxHeight = 180.dp,
                            )
                        }
                    }
                    is UpdateDialogState.Downloading -> {
                        val total = state.info.sizeBytes
                        val copied = (total * state.progress.coerceIn(0f, 1f)).toLong()
                        Text(
                            text = "${(state.progress * 100).toInt()}% · " +
                                "${formatMegabytes(copied)} / ${formatMegabytes(total)}",
                            color = TextSecondary,
                        )
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = AppleBlue,
                            // 轨道是"未完成"的底衬,不应与填充色抢视觉;
                            // 用项目语义的 DividerGray(≈M3 默认轨道色档位),
                            // 不用文本灰 TextSecondary。
                            trackColor = DividerGray,
                        )
                    }
                    is UpdateDialogState.Failed -> Text(state.message, color = ErrorRed)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateDialogState.Available -> TextButton(
                    onClick = onStartDownload,
                    modifier = Modifier.focusRequester(primaryFocus),
                ) {
                    Text("更新", color = TextPrimary)
                }
                is UpdateDialogState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text("下载中", color = TextSecondary)
                }
                is UpdateDialogState.Failed -> TextButton(
                    onClick = onRetryDownload,
                    modifier = Modifier.focusRequester(primaryFocus),
                ) {
                    Text("重试", color = TextPrimary)
                }
            }
        },
        dismissButton = {
            when {
                state !is UpdateDialogState.Downloading -> {
                    TextButton(onClick = onDismiss) {
                        Text("稍后", color = TextSecondary)
                    }
                }
                allowDismissDuringDownload -> {
                    TextButton(onClick = onDismiss) {
                        Text("后台下载", color = TextSecondary)
                    }
                }
            }
        },
        containerColor = CardGray,
    )
}

/** 更新包体积统一按 MB 展示（APK 体量下 KB 无意义），超过 1 GB 换算 GB。 */
private fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    // 显式 Locale:部分设备默认 Locale 的数字分隔符会把 "1.5" 格式成 "1,5"
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    }
}
