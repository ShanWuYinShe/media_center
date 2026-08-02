package com.example.mediacenter.ui.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
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
) {
    AlertDialog(
        onDismissRequest = {
            // 下载中不允许关闭,避免任务与 UI 状态脱节
            if (state !is UpdateDialogState.Downloading) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is UpdateDialogState.Available -> "发现新版本 ${state.info.versionName}"
                    is UpdateDialogState.Downloading -> "正在下载 ${state.info.versionName}..."
                    is UpdateDialogState.Failed -> "下载更新失败"
                },
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            when (state) {
                is UpdateDialogState.Available -> Text(
                    state.info.releaseNotes ?: "新版本可用，是否更新？",
                    color = TextSecondary,
                )
                is UpdateDialogState.Downloading -> {
                    Text(
                        "下载进度 ${(state.progress * 100).toInt()}%",
                        color = TextSecondary,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppleBlue,
                        trackColor = TextSecondary,
                    )
                }
                is UpdateDialogState.Failed -> Text(
                    state.message,
                    color = ErrorRed,
                )
            }
        },
        confirmButton = {
            when (state) {
                is UpdateDialogState.Available -> TextButton(onClick = onStartDownload) {
                    Text("更新", color = TextPrimary)
                }
                is UpdateDialogState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text("下载中", color = TextSecondary)
                }
                is UpdateDialogState.Failed -> TextButton(onClick = onRetryDownload) {
                    Text("重试", color = TextPrimary)
                }
            }
        },
        dismissButton = {
            if (state !is UpdateDialogState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text("稍后", color = TextSecondary)
                }
            }
        },
        containerColor = CardGray,
    )
}
