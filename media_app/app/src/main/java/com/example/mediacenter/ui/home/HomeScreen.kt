package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.util.NetworkRestoreEffect

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSeeAll: (categoryId: String, title: String) -> Unit,
) {
    // 更新管理器与 ViewModel 在组合体内创建:AppUpdateManager 需要 context,
    // 下载状态随 ViewModel 存活(离开首页不中断)。
    val context = LocalContext.current
    val updateManager = remember {
        AppUpdateManager(
            context.applicationContext,
            MediaApi(BuildConfig.MEDIA_SERVER_URL),
        )
    }
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(updateManager))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            onDismiss = viewModel::dismissUpdate,
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let { viewModel.startUpdateDownload(it.info) }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let { viewModel.startUpdateDownload(it.info) }
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
            else -> items(state.categories, key = { it.id }) { category ->
                CategoryRow(
                    categoryName = category.title,
                    videos = state.categoryVideos[category.id].orEmpty(),
                    onVideoClick = onVideoClick,
                    onSeeAll = { onSeeAll(category.id, category.title) },
                    loadFailed = state.categoryErrors.contains(category.id),
                )
            }
        }
    }
}
