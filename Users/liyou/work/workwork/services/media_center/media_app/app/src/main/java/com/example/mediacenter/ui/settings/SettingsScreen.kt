package com.example.mediacenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult
import com.example.mediacenter.update.UpdateCheckResult
import com.example.mediacenter.update.UpdateDownloadResult
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.components.TvScrollableText
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }

    // ── 手动检查更新 ──
    // 与首页启动时的自动检查共用 AppUpdateManager / UpdateDialog；区别在于
    // 这里是用户主动触发，无论结果如何都给出明确反馈（含"已是最新版本"）。
    val updateManager = remember {
        AppUpdateManager(
            context.applicationContext,
            MediaApi(BuildConfig.MEDIA_SERVER_URL),
        )
    }
    var updateState by remember { mutableStateOf<UpdateDialogState?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

    fun checkForUpdate() {
        if (checkingUpdate || updateState != null) return
        checkingUpdate = true
        feedback = null
        scope.launch {
            when (val result = updateManager.check()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    feedback = null
                    updateState = UpdateDialogState.Available(result.update)
                }
                is UpdateCheckResult.Failed -> feedback = result.message
                UpdateCheckResult.NoUpdate -> feedback = "已是最新版本"
            }
            checkingUpdate = false
        }
    }

    fun startUpdateDownload(info: AppUpdateInfo) {
        if (updateState is UpdateDialogState.Downloading) return
        scope.launch {
            updateState = UpdateDialogState.Downloading(info, 0f)
            // 下载/安装流程与首页保持一致:下载(带进度)→校验→拉起安装器;
            // 离开设置页会取消协程,AppUpdateManager 会清理半成品文件。
            val result = updateManager.download(info) { progress ->
                updateState = UpdateDialogState.Downloading(info, progress)
            }
            when (result) {
                is UpdateDownloadResult.Ready -> {
                    updateState = null
                    when (val install = updateManager.launchInstaller(result.apk)) {
                        is InstallLaunchResult.NeedsPermission -> {
                            // Android 8+ 未开"安装未知应用"权限:引导去系统设置,返回后可重试。
                            updateManager.launchInstallPermissionSettings()
                            updateState = UpdateDialogState.Failed(
                                result.update,
                                "请先允许安装未知应用，然后重新下载安装",
                            )
                        }
                        is InstallLaunchResult.Failed -> {
                            updateState = UpdateDialogState.Failed(result.update, install.message)
                        }
                        is InstallLaunchResult.Started -> Unit
                    }
                }
                is UpdateDownloadResult.Failed -> {
                    updateState = UpdateDialogState.Failed(info, result.message)
                }
            }
        }
    }

    fun clearUpdateCache() {
        // 删文件是磁盘 IO,移出主线程避免卡顿(与"查看日志"一致)。
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "app-updates")
                var n = 0
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { file -> if (file.delete()) n++ }
                }
                n
            }
            feedback = if (deleted > 0) "已清除 $deleted 个更新包" else "无更新缓存可清除"
        }
    }

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            onDismiss = { updateState = null },
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let { startUpdateDownload(it.info) }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let { startUpdateDownload(it.info) }
            },
        )
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title, color = TextPrimary) },
            text = { Text(action.message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    action.onConfirm()
                    confirmAction = null
                }) { Text("确认", color = TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = CardGray,
        )
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("应用日志", color = TextPrimary) },
            text = {
                // 日志用遥控器可滚动文本：上/下键步进滚动(原 verticalScroll 对 D-pad 无效)
                TvScrollableText(
                    text = logs.ifEmpty { "(空)" },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                    maxHeight = 400.dp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) { Text("关闭", color = TextPrimary) }
            },
            containerColor = CardGray,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(onClick = onBack, contentDescription = "返回") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
            }
            Text(
                text = "设置",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionTitle("关于")
                InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("服务端", BuildConfig.MEDIA_SERVER_URL)
            }
            item {
                SectionTitle("版本更新")
                ActionRow(
                    label = if (checkingUpdate) "正在检查更新…" else "检查更新",
                    enabled = !checkingUpdate,
                    onClick = { checkForUpdate() },
                )
            }
            item {
                SectionTitle("数据")
                ActionRow(
                    label = "清除断点续播记录",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除断点续播记录",
                            message = "将删除保存的播放位置，下次播放不会自动续播。",
                            onConfirm = {
                                PlaybackPositionStore.clear(context)
                                feedback = "已清除"
                            },
                        )
                    },
                )
                ActionRow(
                    label = "清除更新缓存",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除更新缓存",
                            message = "将删除已下载的 App 更新包。",
                            onConfirm = { clearUpdateCache() },
                        )
                    },
                )
                ActionRow(
                    label = "清除日志",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除日志",
                            message = "将删除本地日志文件，用于故障排查的日志将不可用。",
                            onConfirm = {
                                AppLog.clear()
                                feedback = "已清除"
                            },
                        )
                    },
                )
            }
            item {
                SectionTitle("诊断")
                ActionRow(
                    label = "查看日志",
                    onClick = {
                        // 读日志是磁盘 I/O，移出主线程避免卡顿。
                        scope.launch {
                            logs = withContext(Dispatchers.IO) { AppLog.tail(300) }
                            showLogs = true
                        }
                    },
                )
            }
            feedback?.let { message ->
                item {
                    Text(
                        text = message,
                        color = TextTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private data class ConfirmAction(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = TextTertiary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(420.dp),
        )
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).requiredHeight(48.dp),
        colors = ButtonDefaults.colors(
            containerColor = CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
            disabledContentColor = TextSecondary,
        ),
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
