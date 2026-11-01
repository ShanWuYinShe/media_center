package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.PagedVideoGrid
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateHolder
import com.example.mediacenter.util.NetworkRestoreEffect

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    // 继续观看直达播放页并携带分页(TV"继续看"标准动线)
    onPlayVideo: (VideoSummary, Int) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory())
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshWatchHistory()
        }
    }

    updateState?.let { dialogState ->
        UpdateDialog(
            state = dialogState,
            onDismiss = { AppUpdateHolder.dismissUpdate(allowDuringDownload = true) },
            allowDismissDuringDownload = true,
            onStartDownload = {
                (dialogState as? UpdateDialogState.Available)?.let {
                    AppUpdateHolder.startUpdateDownload(it.info)
                }
            },
            onRetryDownload = {
                (dialogState as? UpdateDialogState.Failed)?.let {
                    AppUpdateHolder.startUpdateDownload(it.info)
                }
            },
        )
    }

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

    NetworkRestoreEffect(onRestored = {
        if (state.error != null) viewModel.retry()
    })

    // 首屏焦点落在当前分类标签：老人打开电视先看到"我在哪一类"，
    // 左右键即可换类，不需要精确瞄准某个卡片或图标。
    val selectedTabFocus = remember { FocusRequester() }
    val gridFirstFocus = remember { FocusRequester() }
    val categoriesReady = state.categories.isNotEmpty()
    LaunchedEffect(categoriesReady) {
        if (categoriesReady && viewModel.consumeInitialFocus()) {
            runCatching { selectedTabFocus.requestFocus() }
        }
    }

    val tabs = remember(state.categories) {
        buildList {
            add(HomeTab(HomeContinueTabId, "继续观看"))
            state.categories.forEach { add(HomeTab(it.id, it.title)) }
        }
    }
    val continueEntries = remember(state.watchEntries, state.currentProvider) {
        state.watchEntries.filter {
            it.provider == state.currentProvider && it.positionSeconds >= 30
        }
    }
    val continuePageById = remember(continueEntries) {
        continueEntries.associate { it.videoId to it.page }
    }
    val continueProgressById = remember(continueEntries) {
        continueEntries
            .filter { it.durationSeconds > 0 }
            .associate {
                it.videoId to
                    (it.positionSeconds.toFloat() / it.durationSeconds).coerceIn(0f, 1f)
            }
    }
    val showingContinue = state.selectedTab == HomeContinueTabId
    val selectedCategory = state.categories.firstOrNull { it.id == state.selectedTab }
    val pageTitle = if (showingContinue) "继续观看" else selectedCategory?.title.orEmpty()
    val countLabel = when {
        showingContinue -> if (continueEntries.isEmpty()) "" else "${continueEntries.size} 个进行中"
        state.total > 0 -> "共 ${state.total} 部"
        state.items.isNotEmpty() -> "共 ${state.items.size} 部"
        else -> ""
    }
    val hasGridContent = if (showingContinue) {
        continueEntries.isNotEmpty()
    } else {
        state.items.isNotEmpty()
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            searchDownFocusRequester = if (categoriesReady) selectedTabFocus else null,
        )
        HomeCategoryTabs(
            tabs = tabs,
            selectedId = state.selectedTab,
            onSelect = viewModel::selectTab,
            selectedFocusRequester = selectedTabFocus,
            firstCardDownFocusRequester = if (hasGridContent) gridFirstFocus else null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (categoriesReady && pageTitle.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ScreenHorizontalPadding,
                        end = ScreenHorizontalPadding,
                        top = 10.dp,
                        bottom = 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pageTitle,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (countLabel.isNotBlank()) {
                    Text(
                        text = countLabel,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.categories.isEmpty() && state.isLoading ->
                    LoadingIndicator(modifier = Modifier.fillMaxSize())
                state.error != null -> ErrorView(
                    message = state.error!!,
                    onRetry = viewModel::retry,
                    autoFocusRetry = true,
                    modifier = Modifier.fillMaxSize(),
                )
                showingContinue -> {
                    if (continueEntries.isEmpty()) {
                        EmptyStateView(
                            message = "还没有观看记录",
                            detail = "看过的视频会自动出现在这里，方便接着看",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val videos = continueEntries.map { entry ->
                            val summary = entry.toVideoSummary()
                            if (entry.page > 0) {
                                summary.copy(title = "第 ${entry.page + 1} 页 · ${summary.title}")
                            } else {
                                summary
                            }
                        }
                        PagedVideoGrid(
                            items = videos,
                            onItemClick = { video ->
                                onPlayVideo(video, continuePageById[video.id] ?: 0)
                            },
                            onLoadMore = {},
                            isLoadingMore = false,
                            loadMoreFailed = false,
                            onRetryLoadMore = {},
                            modifier = Modifier.fillMaxSize(),
                            firstItemFocusRequester = gridFirstFocus,
                            firstItemUpFocusRequester = selectedTabFocus,
                            progressById = continueProgressById,
                        )
                    }
                }
                state.isLoading && state.items.isEmpty() ->
                    LoadingIndicator(modifier = Modifier.fillMaxSize())
                state.items.isEmpty() -> EmptyStateView(
                    message = "当前分类暂无内容",
                    detail = if (state.currentProvider == "youtube") {
                        "可以按搜索键直接搜索 YouTube 内容"
                    } else {
                        "服务端还没有已上线的视频"
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> PagedVideoGrid(
                    items = state.items,
                    onItemClick = onVideoClick,
                    onLoadMore = viewModel::loadMore,
                    isLoadingMore = state.isLoadingMore,
                    loadMoreFailed = state.loadMoreError != null,
                    onRetryLoadMore = viewModel::retryLoadMore,
                    modifier = Modifier.fillMaxSize(),
                    firstItemFocusRequester = gridFirstFocus,
                    firstItemUpFocusRequester = selectedTabFocus,
                )
            }
        }
    }
}
