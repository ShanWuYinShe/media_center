package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.ProviderSelectDialog
import com.example.mediacenter.ui.components.TopBar
import com.example.mediacenter.ui.update.UpdateDialog
import com.example.mediacenter.ui.update.UpdateDialogState
import com.example.mediacenter.update.AppUpdateHolder
import com.example.mediacenter.util.NetworkRestoreEffect
import com.example.mediacenter.util.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    // 「继续观看」行与 Hero 的播放:直达播放页并携带分页(TV"继续看"标准动线)
    onPlayVideo: (VideoSummary, Int) -> Unit,
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
            // SP 读含 JSON 解码:移 IO 线程,避免每次回首页都在主线程做磁盘 IO
            watchEntries = withContext(Dispatchers.IO) { WatchHistory.entries(context) }
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

    // 首次进入首页把焦点落在搜索入口:默认焦点落在设置图标上不符合"先找内容"
    // 的直觉,而聚焦 Hero 按钮会让 LazyColumn 把顶栏滚出屏幕。
    // rememberSaveable 保证从详情/播放返回时不抢回焦点(由 focusRestorer 负责)。
    val searchFocus = remember { FocusRequester() }
    var initialFocusApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialFocusApplied) {
            searchFocus.requestFocus()
            initialFocusApplied = true
        }
    }

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
                searchFocusRequester = searchFocus,
            )
        }
        // ── 继续观看 ──
        // 只显示当前 provider 的条目(播放解析按当前源路由,跨源条目无法播放);
        // 位置不足 30s 的条目在登记端已过滤。放在分类行之前,回到首页即见。
        // 点击直达播放页并带分页(跳过详情页)。
        val continueEntries = watchEntries.filter {
            it.provider == state.currentProvider && it.positionSeconds >= 30
        }
        val continuePageById = continueEntries.associate { it.videoId to it.page }

        // ── Hero ──
        // 有继续观看时优先"接着看"(带进度),否则用首个有内容的分类第一条
        // 作为精选,首屏给出明确的视觉重心与两个动作。
        val heroItem: FeaturedVideo? = continueEntries.firstOrNull()?.let { entry ->
            FeaturedVideo(
                video = entry.toVideoSummary(),
                page = entry.page,
                progress = if (entry.durationSeconds > 0) {
                    (entry.positionSeconds.toFloat() / entry.durationSeconds).coerceIn(0f, 1f)
                } else {
                    null
                },
                badge = "继续观看",
                meta = continueMeta(entry.positionSeconds, entry.durationSeconds),
                description = entry.toVideoSummary().description,
            )
        } ?: state.categories.firstOrNull { !state.categoryVideos[it.id].isNullOrEmpty() }?.let { category ->
            val video = state.categoryVideos[category.id]!!.first()
            val duration = video.durationSeconds?.takeIf { it > 0 }?.let { formatSeconds(it) }
            FeaturedVideo(
                video = video,
                badge = "精选",
                meta = listOfNotNull(category.title, duration).joinToString(" · "),
                description = video.description,
            )
        }
        heroItem?.let { hero ->
            item(key = "hero") {
                val isContinue = continueEntries.firstOrNull()?.videoId == hero.video.id
                FeaturedHero(
                    item = hero,
                    onPlay = {
                        // 精选 Hero 直接播放时也登记观看历史,否则播放器退出时
                        // updatePosition 找不到条目,这条内容永远进不了"继续观看"。
                        if (!isContinue) {
                            WatchHistory.upsert(
                                context,
                                WatchEntry(
                                    videoId = hero.video.id,
                                    provider = state.currentProvider,
                                    title = hero.video.title,
                                    thumbnailUrl = hero.video.thumbnailUrl.orEmpty(),
                                    durationSeconds = hero.video.durationSeconds ?: 0L,
                                    page = hero.page,
                                ),
                            )
                        }
                        onPlayVideo(hero.video, hero.page)
                    },
                    onDetails = { onVideoClick(hero.video) },
                )
            }
        }

        if (continueEntries.isNotEmpty()) {
            item {
                CategoryRow(
                    categoryName = "继续观看",
                    videos = continueEntries.map { entry ->
                        // 多P条目标题前缀页码,一眼分清看的是哪一集/哪一分P
                        val summary = entry.toVideoSummary()
                        if (entry.page > 0) {
                            summary.copy(title = "第 ${entry.page + 1} 页 · ${summary.title}")
                        } else {
                            summary
                        }
                    },
                    onVideoClick = { video ->
                        onPlayVideo(video, continuePageById[video.id] ?: 0)
                    },
                    progressById = continueEntries
                        .filter { it.durationSeconds > 0 }
                        .associate { entry ->
                            entry.videoId to (entry.positionSeconds.toFloat() / entry.durationSeconds).coerceIn(0f, 1f)
                        },
                )
            }
        }
        when {
            // 首次加载(还没有任何分类)才铺骨架屏;已有缓存内容时静默刷新,
            // 不打断正在浏览的用户。
            state.isLoading && state.categories.isEmpty() -> item(key = "skeleton") { HomeSkeleton() }
            state.error != null -> item(key = "error") {
                // 全屏错误层必须给遥控器一个明确的焦点落点,否则方向键全部落空。
                // LazyColumn item 的 fillMaxSize 没有有界高度,用父容器比例撑开,
                // 让错误/空态看起来是"页面中央"而不是贴着顶部一条。
                ErrorView(
                    message = state.error!!,
                    onRetry = { viewModel.loadHome() },
                    autoFocusRetry = true,
                    modifier = Modifier.fillMaxWidth().fillParentMaxHeight(0.72f),
                )
            }
            state.categories.isEmpty() -> item(key = "empty") {
                EmptyStateView(
                    message = "暂无分类数据",
                    detail = if (state.currentProvider == "youtube") {
                        "YouTube 内容可直接搜索播放，按顶栏搜索键开始"
                    } else {
                        "服务端还没有已上线的分类内容"
                    },
                    modifier = Modifier.fillMaxWidth().fillParentMaxHeight(0.72f),
                )
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
