package com.example.mediacenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.SearchHistory
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.data.providerDisplayName
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.update.AppUpdateHolder
import com.example.mediacenter.update.UpdateCheckResult
import com.example.mediacenter.player.PlaybackMediaCache
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.components.TvBackButton
import com.example.mediacenter.ui.components.TvScrollableText
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.ScreenVerticalPadding
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
    // 与首页共用进程级协调器(AppUpdateHolder):同一份状态流,下载在任何页面
    // 发起、任何页面都能观察进度;这里是用户主动触发,无论结果如何都给出
    // 明确反馈(含"已是最新版本")。
    val updateState by AppUpdateHolder.updateState.collectAsStateWithLifecycle()
    var checkingUpdate by remember { mutableStateOf(false) }

    // ── 服务端版本 ──
    // 排障时用户可直接报出线上服务端构建版本(/health 公开端点)。
    var serverVersion by remember { mutableStateOf("获取中…") }
    LaunchedEffect(Unit) {
        serverVersion = MediaRepositoryHolder.updateApi.serverVersion() ?: "无法获取"
    }

    // ── 网络加速状态 ──
    // 优选 IP 的排障入口:国内连 Cloudflare 走系统 DNS 时命中坏节点会表现为
    // "一直转圈",这里直接显示当前生效的首选节点与最近测速时间。
    var preferredIpStatus by remember { mutableStateOf("未启用") }
    LaunchedEffect(Unit) {
        preferredIpStatus = com.example.mediacenter.net.PreferredIpStore.statusText()
    }

    // ── 播放媒体缓存占用 ──
    // 「清除播放缓存」入口显示当前占用(-1 表示不可用),清除后刷新。
    var mediaCacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        mediaCacheBytes = withContext(Dispatchers.IO) {
            PlaybackMediaCache.cacheSizeBytes(context)
        }
    }

    fun checkForUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        feedback = null
        scope.launch {
            when (val result = AppUpdateHolder.checkSuspend()) {
                is UpdateCheckResult.UpdateAvailable -> feedback = null
                is UpdateCheckResult.Failed -> feedback = result.message
                UpdateCheckResult.NoUpdate -> feedback = "已是最新版本"
            }
            checkingUpdate = false
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

    fun clearMediaCache() {
        // SimpleCache 逐资源移除是磁盘 IO,移出主线程。
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                PlaybackMediaCache.clearAll(context)
            }
            feedback = when {
                removed < 0 -> "清除播放缓存失败，请重试"
                removed == 0 -> "播放缓存已是空的"
                else -> "已清除 $removed 条播放缓存"
            }
            // 刷新入口上的占用显示(清除后应变 0)
            mediaCacheBytes = withContext(Dispatchers.IO) {
                PlaybackMediaCache.cacheSizeBytes(context)
            }
        }
    }

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            // 下载中允许"后台下载":状态在进程级协调器上,离开设置页也不中断
            allowDismissDuringDownload = true,
            onDismiss = { AppUpdateHolder.dismissUpdate(allowDuringDownload = true) },
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let { AppUpdateHolder.startUpdateDownload(it.info) }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let { AppUpdateHolder.startUpdateDownload(it.info) }
            },
        )
    }

    val confirmDialogFocus = remember { FocusRequester() }
    LaunchedEffect(confirmAction) {
        if (confirmAction != null) confirmDialogFocus.requestFocus()
    }
    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title, color = TextPrimary) },
            text = { Text(action.message, color = TextSecondary) },
            confirmButton = {
                TvDialogButton(
                    label = "确认",
                    primary = true,
                    onClick = {
                        action.onConfirm()
                        confirmAction = null
                    },
                    modifier = Modifier.focusRequester(confirmDialogFocus),
                )
            },
            dismissButton = {
                TvDialogButton(label = "取消", onClick = { confirmAction = null })
            },
            containerColor = CardGray,
        )
    }

    val logsDialogFocus = remember { FocusRequester() }
    LaunchedEffect(showLogs) {
        if (showLogs) logsDialogFocus.requestFocus()
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
                TvDialogButton(
                    label = "关闭",
                    primary = true,
                    onClick = { showLogs = false },
                    modifier = Modifier.focusRequester(logsDialogFocus),
                )
            },
            containerColor = CardGray,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = ScreenVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvBackButton(onClick = onBack)
            Text(
                text = "设置",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
            // 操作结果反馈固定在标题栏右侧:列表本身比 1080p TV 可视区更长,
            // 反馈若跟在列表末尾(旧实现)基本落在屏外,用户按了看不到任何结果。
            feedback?.let { message ->
                Spacer(Modifier.weight(1f))
                Text(
                    text = message,
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                SectionTitle("关于")
                InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("视频源", providerDisplayName(MediaRepositoryHolder.repository.currentProvider))
                InfoRow("服务端", BuildConfig.MEDIA_SERVER_URL)
                InfoRow("服务端版本", serverVersion)
                InfoRow("网络加速", preferredIpStatus)
            }
            item {
                SectionTitle("版本更新")
                ActionRow(
                    label = if (checkingUpdate) "正在检查更新…" else "检查更新",
                    enabled = !checkingUpdate,
                    onClick = { checkForUpdate() },
                )
            }
            item { SectionTitle("数据") }
            item {
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
            }
            item {
                ActionRow(
                    label = "清除观看历史",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除观看历史",
                            message = "将清空首页的「继续观看」列表，播放位置记录保留。",
                            onConfirm = {
                                WatchHistory.clear(context)
                                feedback = "已清除"
                            },
                        )
                    },
                )
            }
            item {
                ActionRow(
                    label = "清除搜索历史",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除搜索历史",
                            message = "将清空搜索页的「最近搜索」记录。",
                            onConfirm = {
                                SearchHistory.clear(context)
                                feedback = "已清除"
                            },
                        )
                    },
                )
            }
            item {
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
            }
            item {
                val sizeLabel = mediaCacheBytes?.takeIf { bytes -> bytes > 0 }
                    ?.let { bytes ->
                        val mb = bytes / 1024 / 1024
                        // <1MB 显示 "<1MB",避免 "(0MB)" 误导用户以为缓存是空的
                        if (mb > 0) " (${mb}MB)" else " (<1MB)"
                    } ?: ""
                ActionRow(
                    label = "清除播放缓存$sizeLabel",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除播放缓存",
                            message = "将清空已缓存的播放媒体片段（预取与边看边存的部分），下次播放重新从网络加载。若正在播放，可能有短暂卡顿后自动恢复。",
                            onConfirm = { clearMediaCache() },
                        )
                    },
                )
            }
            item {
                ActionRow(
                    label = "清除日志",
                    onClick = {
                        confirmAction = ConfirmAction(
                            title = "清除日志",
                            message = "将删除本地日志文件，用于故障排查的日志将不可用。",
                            onConfirm = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { AppLog.clear() }
                                    feedback = "已清除"
                                }
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
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 10.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = 6.dp),
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
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        shape = tvPillButtonShape(),
        border = tvPillButtonBorder(),
        scale = tvButtonFocusScale(),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 2.dp)
            .requiredHeight(52.dp),
        colors = ButtonDefaults.colors(
            // 透明底 + 行内分隔:设置项像"列表",焦点态仍用统一 Apple Blue
            containerColor = CardGray.copy(alpha = 0.6f),
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
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) TextSecondary else TextTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}
