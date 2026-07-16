package com.example.mediacenter.ui.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary

@Composable
fun UpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${updateInfo.versionName}", color = TextPrimary) },
        text = {
            Text(
                updateInfo.releaseNotes ?: "新版本可用，是否更新？",
                color = TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onStartDownload) {
                Text("更新", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后", color = TextSecondary)
            }
        },
        containerColor = CardGray,
    )
}
