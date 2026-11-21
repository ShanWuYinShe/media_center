package com.example.mediacenter.ui.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.PagedVideoGrid
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.components.VideoGridSkeleton
import com.example.mediacenter.ui.theme.CardGray
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
        // 全屏错误自动重试;SWR 后台刷新失败(旧内容仍在屏上)同样重试一次,
        // 否则用户会一直看到过期内容且没有任何提示/恢复入口。
        if (state.error != null || state.staleRefreshFailed) viewModel.retry()
    })

    // 首屏焦点落在当前分类标签：老人打开电视先看到"我在哪一类"，
    // 左右键即可换类，不需要精确瞄准某个卡片或图标。
    val selectedTabFocus = remember { FocusRequester() }
    val gridFirstFocus = remember { FocusRequester() }
    // 顶栏「设置」按钮的焦点入口:分类栏/内容区按 UP 直接落到这里，
    // 不必再一路右移到最右侧才够得着(见 HomeCategoryTabs 的 upFocusRequester)。
    val topBarSettingsFocus = remember { FocusRequester() }
    // 顶栏「搜索」按钮同样可被 UP 命中:焦点落在顶栏区后左右键在
    // 视频源/设置/搜索之间移动即可。
    val topBarSearchFocus = remember { FocusRequester() }
    val emptyActionFocus = remember { FocusRequester() }
    // 记住离开首页前停在哪张卡片：从详情/播放/搜索返回时恢复到该卡片，
    // 不要把焦点丢给顶栏按钮(此前返回后会落在"设置/搜索"上，还要按方向键找回)。
    var lastFocusedVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    var managingContinueVideo by remember { mutableStateOf<VideoSummary?>(null) }
    val context = LocalContext.current
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (lastFocusedVideoId != null) {
            // 在卡片网格浏览时按返回键:平滑将焦点带回当前选中的分类标签，避免误触直接退回桌面
            lastFocusedVideoId = null
            runCatching { selectedTabFocus.requestFocus() }
        } else if (now - lastBackPressTime < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }
    val categoriesReady = state.categories.isNotEmpty()
    LaunchedEffect(categoriesReady, lastFocusedVideoId) {
        if (!categoriesReady) return@LaunchedEffect
        // 首次进入请求当前分类标签；从其他页面返回且没有可恢复卡片时也回到标签，
        // 保证焦点永远落在内容区而不是顶栏。
        if (viewModel.consumeInitialFocus() || lastFocusedVideoId == null) {
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
            searchFocusRequester = topBarSearchFocus,
            settingsFocusRequester = topBarSettingsFocus,
        )
        val firstNormalCategory = state.categories.firstOrNull()
        val downFocusRequester = when {
            hasGridContent -> gridFirstFocus
            state.error != null -> emptyActionFocus
            showingContinue -> if (firstNormalCategory != null) emptyActionFocus else null
            state.items.isEmpty() -> emptyActionFocus
            else -> null
        }

        HomeCategoryTabs(
            tabs = tabs,
            selectedId = state.selectedTab,
            onSelect = viewModel::selectTab,
            selectedFocusRequester = selectedTabFocus,
            firstCardDownFocusRequester = downFocusRequester,
            modifier = Modifier.fillMaxWidth(),
            // 任意分类标签按 UP 都能进入顶栏(此前只能一路右移到最右侧)。
            upFocusRequester = topBarSettingsFocus,
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
                    VideoGridSkeleton(modifier = Modifier.fillMaxSize())
                state.error != null -> ErrorView(
                    message = state.error!!,
                    onRetry = viewModel::retry,
                    autoFocusRetry = true,
                    retryFocusRequester = emptyActionFocus,
                    modifier = Modifier.fillMaxSize(),
                )
                showingContinue -> {
                    if (continueEntries.isEmpty()) {
                        EmptyStateView(
                            message = "还没有观看记录",
                            detail = "看过的视频会自动出现在这里，方便接着看",
                            actionLabel = if (firstNormalCategory != null) "浏览推荐" else null,
                            onAction = if (firstNormalCategory != null) {
                                { viewModel.selectTab(firstNormalCategory.id) }
                            } else {
                                null
                            },
                            actionFocusRequester = emptyActionFocus,
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
                            onItemLongClick = { video ->
                                managingContinueVideo = video
                            },
                            onLoadMore = {},
                            isLoadingMore = false,
                            loadMoreFailed = false,
                            onRetryLoadMore = {},
                            modifier = Modifier.fillMaxSize(),
                            firstItemFocusRequester = gridFirstFocus,
                            firstItemUpFocusRequester = selectedTabFocus,
                            progressById = continueProgressById,
                            resetKey = state.selectedTab,
                            initialFocusItemId = lastFocusedVideoId,
                            onItemFocused = { lastFocusedVideoId = it },
                        )
                    }
                }
                state.isLoading && state.items.isEmpty() ->
                    VideoGridSkeleton(modifier = Modifier.fillMaxSize())
                state.items.isEmpty() -> EmptyStateView(
                    message = "当前分类暂无内容",
                    detail = if (state.currentProvider == "youtube") {
                        "可以按搜索键直接搜索 YouTube 内容"
                    } else {
                        "服务端还没有已上线的视频"
                    },
                    actionLabel = if (state.currentProvider == "youtube") "去搜索" else "刷新",
                    onAction = if (state.currentProvider == "youtube") onSearchClick else viewModel::retry,
                    actionFocusRequester = emptyActionFocus,
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
                    resetKey = state.selectedTab,
                    initialFocusItemId = lastFocusedVideoId,
                    onItemFocused = { lastFocusedVideoId = it },
                )
            }
        }
    }

    if (managingContinueVideo != null) {
        val targetVideo = managingContinueVideo!!
        WatchHistoryManageDialog(
            title = targetVideo.title,
            onDismiss = { managingContinueVideo = null },
            onRemove = {
                viewModel.removeWatchHistory(targetVideo.id)
                managingContinueVideo = null
            },
            onRestart = {
                viewModel.restartWatchHistory(targetVideo.id)
                managingContinueVideo = null
                onPlayVideo(targetVideo, 0)
            },
        )
    }
}

@Composable
private fun WatchHistoryManageDialog(
    title: String,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onRestart: () -> Unit,
) {
    val removeFocus = remember { FocusRequester() }
    val restartFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { removeFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "管理该视频的观看记录与进度：",
                    color = com.example.mediacenter.ui.theme.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TvDialogButton(
                    text = "从继续观看中移除",
                    primary = true,
                    onClick = onRemove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(removeFocus)
                        .focusProperties {
                            down = restartFocus
                        },
                )
                TvDialogButton(
                    text = "重新从头播放",
                    primary = false,
                    onClick = onRestart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(restartFocus)
                        .focusProperties {
                            up = removeFocus
                            down = cancelFocus
                        },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TvDialogButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(cancelFocus)
                    .focusProperties {
                        up = restartFocus
                    },
            )
        },
        containerColor = CardGray,
    )
}
