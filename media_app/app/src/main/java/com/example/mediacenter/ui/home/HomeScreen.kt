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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateHolder
import com.example.mediacenter.util.NetworkRestoreEffect

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSeeAll: (categoryId: String, title: String) -> Unit,
) {
    // 更新状态在进程级协调器(AppUpdateHolder)上:离开首页下载不中断,
    // 进设置页看到的是同一份下载进度与弹窗状态。
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory())
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    // ── 继续观看 ──
    // 每次回到 RESUMED 重读观看历史:从播放页返回后行内容立即反映最新进度。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var watchEntries by remember { mutableStateOf<List<WatchEntry>>(emptyList()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            watchEntries = WatchHistory.entries(context)
        }
    }

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            onDismiss = { AppUpdateHolder.dismissUpdate(allowDuringDownload = true) },
            // 下载由进程级协调器持有,弹窗关闭/离开首页返回后继续,可“后台下载”
            allowDismissDuringDownload = true,
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let { AppUpdateHolder.startUpdateDownload(it.info) }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let { AppUpdateHolder.startUpdateDownload(it.info) }
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
        // ── 继续观看 ──
        // 只显示当前 provider 的条目(播放解析按当前源路由,跨源条目无法播放);
        // 位置不足 30s 的条目在登记端已过滤。放在分类行之前,回到首页即见。
        val continueEntries = watchEntries.filter {
            it.provider == state.currentProvider && it.positionSeconds >= 30
        }
        if (continueEntries.isNotEmpty()) {
            item {
                CategoryRow(
                    categoryName = "继续观看",
                    videos = continueEntries.map { it.toVideoSummary() },
                    onVideoClick = onVideoClick,
                    progressById = continueEntries
                        .filter { it.durationSeconds > 0 }
                        .associate { entry ->
                            entry.videoId to (entry.positionSeconds.toFloat() / entry.durationSeconds).coerceIn(0f, 1f)
                        },
                )
            }
        }
        when {
            state.isLoading -> item { LoadingIndicator() }
            state.error != null -> item { ErrorView(message = state.error!!, onRetry = { viewModel.loadHome() }) }
            state.categories.isEmpty() -> item {
                ErrorView(message = "\u6682\u65E0\u5206\u7C7B\u6570\u636E")
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
