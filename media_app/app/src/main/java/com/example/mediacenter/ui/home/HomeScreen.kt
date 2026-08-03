package com.example.mediacenter.ui.home

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.AppUpdateInfo
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult
import com.example.mediacenter.update.UpdateCheckResult
import com.example.mediacenter.update.UpdateDownloadResult
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSeeAll: (categoryId: String, title: String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // App 内更新:启动检查一次,有新版弹窗确认后下载安装
    val context = LocalContext.current
    val updateManager = remember {
        AppUpdateManager(
            context.applicationContext,
            MediaApi(BuildConfig.MEDIA_SERVER_URL),
        )
    }
    var updateState by remember { mutableStateOf<UpdateDialogState?>(null) }
    // 进程生命周期内只检查一次:从详情页/播放页返回首页时不应重复发请求
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun startDownload(info: AppUpdateInfo) {
        updateState = UpdateDialogState.Downloading(info, 0f)
        scope.launch {
            when (val result = updateManager.download(info) { progress ->
                // 下载回调发生在 IO 线程,切回主线程更新 Compose 状态
                mainHandler.post { updateState = UpdateDialogState.Downloading(info, progress) }
            }) {
                is UpdateDownloadResult.Ready -> {
                    updateState = null
                    when (val install = updateManager.launchInstaller(result.apk)) {
                        is InstallLaunchResult.NeedsPermission -> {
                            // Android 8+ 未开"安装未知应用"权限:引导去系统设置,返回后提示重试。
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

    LaunchedEffect(Unit) {
        if (updateChecked) return@LaunchedEffect
        updateChecked = true
        when (val result = updateManager.check()) {
            is UpdateCheckResult.UpdateAvailable -> {
                updateState = UpdateDialogState.Available(result.update)
            }
            else -> Unit
        }
    }

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            onDismiss = { updateState = null },
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let { startDownload(it.info) }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let { startDownload(it.info) }
            },
        )
    }

    // 视频源切换:仅当存在多个 provider 时显示入口
    var showProviderDialog by remember { mutableStateOf(false) }
    if (showProviderDialog) {
        ProviderSelectDialog(
            providers = state.providers,
            currentProvider = state.currentProvider,
            onSelect = { provider ->
                showProviderDialog = false
                viewModel.selectProvider(provider)
            },
            onDismiss = { showProviderDialog = false },
        )
    }

    // 断网恢复:网络可用且当前处于错误态时自动重载首页
    NetworkRestoreEffect(onRestored = {
        if (state.error != null) viewModel.loadHome()
    })

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            TopBar(
                onSearchClick = onSearchClick,
                onSettingsClick = onSettingsClick,
                providerLabel = state.providers
                    .firstOrNull { it.id == state.currentProvider }
                    ?.let { it.name ?: it.id }
                    ?: state.currentProvider.takeIf { it.isNotEmpty() },
                onProviderClick = if (state.providers.size > 1) {
                    { showProviderDialog = true }
                } else {
                    null
                },
            )
        }
        when {
            state.isLoading -> item { LoadingIndicator() }
            state.error != null -> item { ErrorView(state.error!!, onRetry = { viewModel.loadHome() }) }
            state.categories.isEmpty() -> item {
                ErrorView("\u6682\u65E0\u5206\u7C7B\u6570\u636E")
            }
            else -> items(state.categories) { category ->
                CategoryRow(
                    categoryName = category.title,
                    videos = state.categoryVideos[category.id].orEmpty(),
                    onVideoClick = onVideoClick,
                    onSeeAll = { onSeeAll(category.id, category.title) },
                )
            }
        }
    }
}
