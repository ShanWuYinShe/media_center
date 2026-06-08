package com.example.mediacenter.ui


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.AppleTvBackground
import com.example.mediacenter.ui.components.CategoryRail
import com.example.mediacenter.ui.components.EmptyState
import com.example.mediacenter.ui.components.ErrorState
import com.example.mediacenter.ui.components.LoadingState
import com.example.mediacenter.ui.components.PauseGlyph
import com.example.mediacenter.ui.components.PlayGlyph
import com.example.mediacenter.ui.components.PosterGrid
import com.example.mediacenter.ui.components.PrimaryPillButton
import com.example.mediacenter.ui.components.SearchIcon
import com.example.mediacenter.ui.components.SearchModeTopBar
import com.example.mediacenter.ui.components.SelectablePillButton
import com.example.mediacenter.ui.components.SecondaryPillButton
import com.example.mediacenter.ui.components.tvFocusableLite
import com.example.mediacenter.ui.theme.AppleTvColors
import com.example.mediacenter.ui.theme.AppleTvShapes
import com.example.mediacenter.ui.theme.AppleTvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 顶层 App composable。
 * 路由三个屏幕:Search(默认)/ Detail(选中一个视频后)/ Player(开始播放后)。
 * 数据流签名与重构前完全一致,只换 UI 实现。
 *
 * Back 键处理(P0-1):
 *   - Player / Detail 屏幕拦截 Back,调用 onBack() 进行屏幕级返回。
 *   - Home 屏幕**不消费** Back 事件,让 Activity 默认 `finish()` 接管 → 用户能退出 app。
 *   - SearchPanel 打开时由 HomeScreen 内部独立 BackHandler 优先关闭面板。
 */
@Composable
fun MediaCenterApp(
    state: MediaUiState,
    onSearch: (String) -> Unit,
    onSelectCategory: (ContentCategory) -> Unit,
    onSelect: (VideoSummary) -> Unit,
    onPlay: (PlaybackOption) -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onLoadMore: () -> Unit,
    onPrefetchVisibleDetails: (List<VideoSummary>) -> Unit,
    onCancelVisibleDetailsPrefetch: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val homeFocusState = rememberSaveable(saver = HomeFocusStateSaver) { HomeFocusState() }
    val categoryRailState = rememberLazyListState()
    val posterGridState = rememberLazyGridState()

    AppleTvTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppleTvColors.DeepSpace,
        ) {
            when {
                state.playing != null -> {
                    val playingIndex = state.playingVideo
                        ?.let { pv -> state.items.indexOfFirst { it.id == pv.id } }
                        ?: -1
                    val nextVideo =
                        if (playingIndex >= 0 && playingIndex + 1 < state.items.size)
                            state.items[playingIndex + 1]
                        else null
                    val previousVideo =
                        if (playingIndex > 0)
                            state.items[playingIndex - 1]
                        else null
                    PlayerScreen(
                        stream = state.playing,
                        hasPrevious = previousVideo != null,
                        previousTitle = previousVideo?.title,
                        hasNext = nextVideo != null || state.canLoadMore,
                        nextTitle = nextVideo?.title,
                        onPlayNext = onPlayNext,
                        onPlayPrevious = onPlayPrevious,
                        onBack = onBack,
                    )
                }
                state.selected != null -> DetailScreen(
                    details = state.selected,
                    error = state.error,
                    onRetry = onRetry,
                    onPlay = onPlay,
                    onBack = onBack,
                )
                else -> HomeScreen(
                    state = state,
                    onSearch = onSearch,
                    onSelectCategory = onSelectCategory,
                    onSelect = onSelect,
                    onLoadMore = onLoadMore,
                    onPrefetchVisibleDetails = onPrefetchVisibleDetails,
                    onCancelVisibleDetailsPrefetch = onCancelVisibleDetailsPrefetch,
                    onRetry = onRetry,
                    onBack = onBack,
                    homeFocusState = homeFocusState,
                    categoryRailState = categoryRailState,
                    posterGridState = posterGridState,
                )
            }
        }
    }
}

private const val HomeFocusAreaCategory = "category"
private const val HomeFocusAreaVideo = "video"
private const val HomeFocusNoCategoryKey = "__no_category__"

private class HomeFocusState(
    area: String = HomeFocusAreaCategory,
    categoryId: String? = null,
    videoFocusByCategory: Map<String, String> = emptyMap(),
) {
    var area by mutableStateOf(area)
        private set
    var categoryId by mutableStateOf(categoryId)
        private set
    var videoFocusByCategory by mutableStateOf(videoFocusByCategory)
        private set

    fun markCategoryFocused(categoryId: String) {
        area = HomeFocusAreaCategory
        this.categoryId = categoryId
    }

    fun markVideoFocused(categoryKey: String, videoId: String) {
        area = HomeFocusAreaVideo
        categoryId = categoryKey.takeUnless { it == HomeFocusNoCategoryKey }
        videoFocusByCategory = videoFocusByCategory + (categoryKey to videoId)
    }

    fun videoIdFor(categoryKey: String): String? = videoFocusByCategory[categoryKey]
}

private val HomeFocusStateSaver = mapSaver(
    save = { state: HomeFocusState ->
        val videoFocusPairs = ArrayList<String>(state.videoFocusByCategory.size * 2)
        state.videoFocusByCategory.forEach { (categoryId, videoId) ->
            videoFocusPairs.add(categoryId)
            videoFocusPairs.add(videoId)
        }
        mapOf(
            "area" to state.area,
            "categoryId" to (state.categoryId ?: ""),
            "videoFocusPairs" to videoFocusPairs,
        )
    },
    restore = { restored ->
        val videoFocusPairs = restored["videoFocusPairs"] as? List<*>
        val videoFocusByCategory = videoFocusPairs
            .orEmpty()
            .chunked(2)
            .mapNotNull { pair ->
                val categoryId = pair.getOrNull(0) as? String
                val videoId = pair.getOrNull(1) as? String
                if (categoryId != null && videoId != null) categoryId to videoId else null
            }
            .toMap()
        HomeFocusState(
            area = (restored["area"] as? String)
                ?.takeIf { it == HomeFocusAreaVideo }
                ?: HomeFocusAreaCategory,
            categoryId = (restored["categoryId"] as? String)?.takeIf { it.isNotEmpty() },
            videoFocusByCategory = videoFocusByCategory,
        )
    },
)

private fun homeFocusCategoryKey(categoryId: String?): String = categoryId ?: HomeFocusNoCategoryKey

// =====================================================================
// HomeScreen:Apple TV 主屏 —— 大标题 / 分类条 / 海报横向轨道
// =====================================================================

@Composable
private fun HomeScreen(
    state: MediaUiState,
    onSearch: (String) -> Unit,
    onSelectCategory: (ContentCategory) -> Unit,
    onSelect: (VideoSummary) -> Unit,
    onLoadMore: () -> Unit,
    onPrefetchVisibleDetails: (List<VideoSummary>) -> Unit,
    onCancelVisibleDetailsPrefetch: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    homeFocusState: HomeFocusState,
    categoryRailState: LazyListState,
    posterGridState: LazyGridState,
) {
    var searchVisible by remember { mutableStateOf(false) }
    var restoreAttempted by remember { mutableStateOf(false) }
    var pendingCategoryFocusId by remember { mutableStateOf<String?>(null) }
    var pendingVideoFocusId by remember { mutableStateOf<String?>(null) }
    var previousCategoryId by remember { mutableStateOf(state.selectedCategoryId) }
    val focusScope = rememberCoroutineScope()
    // 保存每个分类 chip 的 FocusRequester,海报区 UP 时直接调
    // selectedCategoryId 对应的那一个 → 焦点回到当前选中的分类。
    val categoryFocusers = remember { mutableMapOf<String, FocusRequester>() }
    // 放大镜搜索按钮的 FocusRequester:分类 chip 按 UP 时显式回到放大镜,
    // 配合放大镜按 DOWN 回到选中分类 → 搜索↔分类双向可回溯。
    val searchIconFocuser = remember { FocusRequester() }

    LaunchedEffect(state.categories) {
        val categoryIds = state.categories.mapTo(mutableSetOf()) { it.id }
        categoryFocusers.keys.retainAll(categoryIds)
    }

    LaunchedEffect(state.selectedCategoryId) {
        if (state.selectedCategoryId != previousCategoryId) {
            previousCategoryId = state.selectedCategoryId
            pendingVideoFocusId = null
            posterGridState.scrollToItem(0)
        }
    }

    LaunchedEffect(
        restoreAttempted,
        searchVisible,
        state.loading,
        state.error,
        state.categories,
        state.items,
        state.selectedCategoryId,
        homeFocusState.area,
        homeFocusState.categoryId,
        homeFocusState.videoFocusByCategory,
    ) {
        if (restoreAttempted || searchVisible || state.categories.isEmpty()) {
            return@LaunchedEffect
        }

        val currentCategoryKey = homeFocusCategoryKey(state.selectedCategoryId)
        if (homeFocusState.area == HomeFocusAreaVideo && state.loading) {
            return@LaunchedEffect
        }

        val rememberedVideoId = if (homeFocusState.area == HomeFocusAreaVideo && state.error == null) {
            homeFocusState.videoIdFor(currentCategoryKey)
        } else {
            null
        }
        val videoIndex = rememberedVideoId
            ?.let { videoId -> state.items.indexOfFirst { it.id == videoId } }
            ?: -1
        if (rememberedVideoId != null && videoIndex >= 0) {
            posterGridState.scrollToItem(videoIndex)
            pendingVideoFocusId = rememberedVideoId
            restoreAttempted = true
            return@LaunchedEffect
        }

        val categoryTargetId = homeFocusState.categoryId
            ?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.selectedCategoryId?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.categories.firstOrNull()?.id

        if (categoryTargetId != null) {
            val categoryIndex = state.categories.indexOfFirst { it.id == categoryTargetId }
            if (categoryIndex >= 0) {
                categoryRailState.scrollToItem(categoryIndex)
            }
            pendingCategoryFocusId = categoryTargetId
            restoreAttempted = true
        }
    }

    // 关闭搜索页时把焦点交还给合适的目标(主操作优先 + 退出回默认起点):
    //   - 已搜过(state.query 非空)+ 有结果 → 焦点引导到第一个搜索结果,
    //     跟"搜完直接看结果"的自然阅读顺序一致。
    //   - 仅是打开过面板又取消(没搜) → 焦点 + selected 都回到**第一个分类**,
    //     把"分类浏览"重置到明确的默认起点,不保留"上次选中"的中间状态;
    //     这样用户从顶栏"搜索"按钮进来、犹豫一下又取消,能确定自己回到了 app 起点。
    fun closeSearch() {
        searchVisible = false
        if (state.query.isNotEmpty() && state.items.isNotEmpty()) {
            pendingVideoFocusId = state.items.first().id
            focusScope.launch { posterGridState.scrollToItem(0) }
        } else {
            // 退出时**重置**到第一个分类(不沿用上次 selected),既清掉"搜索
            // 入口"的视觉残留,又给用户一个明确的"app 起点"参照。
            state.categories.firstOrNull()?.let { first ->
                if (state.selectedCategoryId != first.id) {
                    onSelectCategory(first)  // 真正切换 selectedCategoryId
                }
                pendingCategoryFocusId = first.id
            }
        }
    }

    // "搜索模式" = 已完成搜索且 SearchPanel 已关闭(state.query 非空 + !searchVisible)。
    //
    // 注意:**不要**把"SearchPanel 展开"算成 inSearchMode —— SearchPanel 自己已经
    // 包含输入框 + 搜索/取消按钮,如果这时顶栏再渲染 SearchModeTopBar,屏幕上
    // 会出现**两个看似输入框**的组件(顶栏的"🔍 搜索…"跟下方的 BasicTextField),
    // 而且顶栏那个根本不可输入,纯粹误导用户。
    //
    // 所以顶栏分三种状态:
    //   1) searchVisible=true            → 顶栏让位(只留背景),让 SearchPanel 独占视觉
    //   2) !searchVisible && query 非空 → 顶栏 = SearchModeTopBar(展示已搜的 query)
    //   3) !searchVisible && query=""   → 顶栏 = 搜索按钮 + 分类条(分类浏览)
    val inSearchMode = !searchVisible && state.query.isNotEmpty()

    // 退出搜索模式:
    //   1) 关闭搜索面板(searchVisible = false)
    //   2) 如果当前内容是搜索结果(有 query),让 ViewModel 重新跑"空关键词 + 第一个分类"
    //      → 清空 query、selectedCategoryId 回到默认,内容区恢复为分类视频
    fun exitSearchMode() {
        searchVisible = false
        if (state.query.isNotEmpty()) {
            state.categories.firstOrNull()?.let { onSelectCategory(it) }
        }
    }

    // 焦点从视频区回退到"当前选中的分类"chip(可回溯:视频列表的来源就是分类,
    // 反向操作就回到分类)。等一帧(withFrameNanos)确保目标 chip 已完成布局,
    // 再 requestFocus,避免对未挂载节点请求焦点失败。
    fun focusSelectedCategory() {
        val targetId = state.selectedCategoryId
            ?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.categories.firstOrNull()?.id
            ?: return
        homeFocusState.markCategoryFocused(targetId)
        categoryFocusers[targetId]?.let { fr ->
            focusScope.launch {
                withFrameNanos { }
                runCatching { fr.requestFocus() }
            }
        }
    }

    // Back 键拦截:用 BackHandler(注册到 Activity 的 OnBackPressedDispatcher),
    // **不依赖 Compose 焦点**。这是关键:SearchPanel 打开时焦点在输入框,且
    // 系统 IME 介入后焦点会离开 Compose 树,导致挂在 Column 上的 onPreviewKeyEvent
    // 收不到 Back 事件 → 事件透传到 Activity.finish → app 退到系统桌面。
    // BackHandler 走 dispatcher,焦点在哪都能拦到。
    //
    // Back 行为(Player / Detail 屏由各自屏幕处理,不在此):
    //   1) searchVisible=true(搜索面板展开)→ closeSearch()(回 app 主页)
    //   2) inSearchMode=true(已搜过,显示结果)→ exitSearchMode()(回 app 主页)
    //   3) 其他(分类浏览)→ enabled=false,不拦截,放行 Activity.finish 退出 app
    BackHandler(enabled = searchVisible || inSearchMode) {
        when {
            searchVisible -> closeSearch()
            inSearchMode -> exitSearchMode()
        }
    }

    // 视频区按 BACK:把焦点回退到"当前选中的分类"chip,而不是退出 app。
    // 这与"第一行海报按 UP 回分类"是同一回退目标,保证视频↔分类可回溯。
    // 仅在分类浏览态(非搜索面板 / 非搜索结果)且焦点确实在视频区时拦截;
    // 焦点在分类区时不拦截 → 放行 Activity.finish,用户可退出 app。
    BackHandler(
        enabled = !searchVisible && !inSearchMode && homeFocusState.area == HomeFocusAreaVideo,
    ) {
        focusSelectedCategory()
    }

    AppleTvBackground {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // 顶栏分三态(全部跟内容区语义对齐):
            //   1) searchVisible=true            → 隐藏顶栏内容,SearchPanel 独占
            //   2) inSearchMode=true(已搜过)    → SearchModeTopBar(关键词 + ×)
            //   3) 默认(分类浏览)                → 右上角放大镜图标(独立一行)+ 分类条
            if (searchVisible) {
                // 搜索面板已自带标题和输入框,顶栏不再放重复且不可用的"搜索"
                // 胶囊(原来那个按钮点了无反应、还能抢焦点,纯属误导)。
                // 仅留出与其它顶栏一致的垂直间距,避免面板上跳。
                Spacer(Modifier.height(60.dp))
            } else if (inSearchMode) {
                SearchModeTopBar(
                    query = state.query,
                    onClear = { exitSearchMode() },
                    // 点搜索栏或按 OK → 重新展开 SearchPanel 继续编辑 query,
                    // 跟 iOS / tvOS 搜索栏行为一致;否则搜完就只能 × 退出,体验生硬。
                    onTapQuery = { searchVisible = true },
                )
            } else {
                // 搜索入口:右上角圆形放大镜图标,**与分类条分离**(不再和分类
                // 挤在同一行),更接近 tvOS 首页的"内容导航 + 角落搜索"布局。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    SearchIconButton(
                        onClick = { searchVisible = true },
                        modifier = Modifier.focusRequester(searchIconFocuser),
                        // 按 DOWN 回到当前选中分类的 chip(可回溯)。延迟到焦点
                        // 搜索时解析,确保此刻分类 chip 的 FocusRequester 已注册。
                        resolveDownTarget = {
                            state.selectedCategoryId?.let { categoryFocusers[it] }
                        },
                    )
                }
                // 分类条:整行独占,放大镜不再占用左侧空间。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryRail(
                        categories = state.categories,
                        selectedCategoryId = state.selectedCategoryId,
                        onSelectCategory = onSelectCategory,
                        contentPadding = PaddingValues(0.dp),
                        state = categoryRailState,
                        pendingFocusCategoryId = pendingCategoryFocusId,
                        onPendingFocusHandled = { categoryId ->
                            if (pendingCategoryFocusId == categoryId) {
                                pendingCategoryFocusId = null
                            }
                        },
                        focuserRegister = { id, fr -> categoryFocusers[id] = fr },
                        // 分类 chip 按 UP 显式回到放大镜搜索按钮(可回溯)。
                        upTarget = searchIconFocuser,
                        onCategoryFocused = { category ->
                            // 焦点回到分类条:退出搜索页,回到分类浏览。
                            searchVisible = false
                            homeFocusState.markCategoryFocused(category.id)
                        },
                    )
                }
            }

            // 内容区:搜索页(展开搜索时)/ 海报网格 / 加载 / 错误 / 空态。
            // 搜索展开时隐藏分类海报,视觉上"跳转"到独立的搜索页。
            if (searchVisible) {
                SearchPanel(
                    initialQuery = state.query,
                    onSearch = { keyword ->
                        onSearch(keyword)
                        closeSearch()
                    },
                    onDismiss = { closeSearch() },
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.error != null -> ErrorState(
                            message = state.error,
                            onRetry = onRetry,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        state.loading && state.items.isEmpty() -> LoadingState(
                            modifier = Modifier.align(Alignment.Center),
                        )
                        state.items.isEmpty() -> EmptyState(
                            message = if (inSearchMode) "没有找到相关内容" else "当前分类暂无内容",
                        )
                        else -> PosterGrid(
                            items = state.items,
                            onSelect = onSelect,
                            canLoadMore = state.canLoadMore,
                            loadingMore = state.loadingMore,
                            onLoadMore = onLoadMore,
                            onPrefetchVisibleDetails = onPrefetchVisibleDetails,
                            onCancelVisibleDetailsPrefetch = onCancelVisibleDetailsPrefetch,
                            state = posterGridState,
                            pendingFocusVideoId = pendingVideoFocusId,
                            onPendingFocusHandled = { videoId ->
                                if (pendingVideoFocusId == videoId) {
                                    pendingVideoFocusId = null
                                }
                            },
                            onVideoFocused = { video ->
                                homeFocusState.markVideoFocused(
                                    categoryKey = homeFocusCategoryKey(state.selectedCategoryId),
                                    videoId = video.id,
                                )
                            },
                            // 第一行海报按 UP 时,强制把焦点引到当前选中的分类 chip
                            onUpFromFirstRow = { focusSelectedCategory() },
                        )
                    }
                    if (state.loading && state.items.isNotEmpty()) {
                        LoadingScrim(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(AppleTvShapes.Button)
            .background(AppleTvColors.DeepSpace.copy(alpha = 0.72f))
            .border(1.dp, AppleTvColors.GlassBorder, AppleTvShapes.Button)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = AppleTvColors.AppleBlue,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "正在加载…",
                color = AppleTvColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// =====================================================================
// SearchPanel:搜索弹窗(标题 + 输入框 + 热门关键词 chip + 操作按钮)
//
// 设计目标:
//   1) **主操作焦点优先**:打开后初始焦点在"搜索"按钮,按 OK 立即搜。
//   2) **快捷入口**:用户首次打开时(没输入),底部"热门关键词" chip 直接可
//      点,避免面对空输入框无措。
//   3) **取消路径清晰**:显式"取消"按钮 + BACK 键都能退出,无歧义。
// =====================================================================

private val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)

// 圆形放大镜搜索按钮(图标-only),用于首页右上角。焦点态蓝底 + 白色外框。
//
// resolveDownTarget:可回溯导航 —— 按 DOWN 时**显式**把焦点交给"当前选中的
// 分类 chip",而不是让系统按空间位置(放大镜在右上角)挑最近的那个 chip。
// 这样"分类按 UP 上来 → 放大镜按 DOWN 下去"是同一个 chip,焦点可回溯;
// 也避免落到错误 chip 触发自动切换分类。
@Composable
private fun SearchIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resolveDownTarget: () -> FocusRequester? = { null },
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val iconColor = AppleTvColors.TextPrimary

    Box(
        modifier = modifier
            .requiredSize(54.dp)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) AppleTvColors.GlassBorderFocused else AppleTvColors.GlassBorder,
                shape = CircleShape,
            )
            .focusProperties {
                resolveDownTarget()?.let { down = it }
            }
            .tvFocusableLite(
                shape = CircleShape,
                focusedScale = 1.08f,
                onClick = onClick,
                onFocusChange = { focused = it },
            ),
        contentAlignment = Alignment.Center,
    ) {
        SearchIcon(color = iconColor, size = 22.dp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchPanel(
    initialQuery: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }

    // 焦点图(显式、可预测):
    //   输入框 --DOWN--> 搜索 ; 搜索 <-LEFT/RIGHT-> 取消 ; 搜索/取消 --UP--> 输入框 ;
    //   搜索/取消 --DOWN--> 第一个热门关键词 ; 关键词 --UP--> 搜索。
    val inputFocuser = remember { FocusRequester() }
    val searchButtonFocuser = remember { FocusRequester() }
    val cancelButtonFocuser = remember { FocusRequester() }
    val firstChipFocuser = remember { FocusRequester() }

    var inputFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 12.dp),
    ) {
        Text(
            text = "搜索",
            color = AppleTvColors.TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 16.dp),
        )

        // 输入框:放大镜 + 文本 + 焦点高亮边框。焦点落到输入框时整条高亮
        // (蓝色边框 + 略亮底色),让用户一眼看出"现在能打字"。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppleTvShapes.Button)
                .background(
                    if (inputFocused) AppleTvColors.GlassFillStrong else AppleTvColors.GlassFill
                )
                .border(
                    width = if (inputFocused) 2.dp else 1.dp,
                    color = if (inputFocused) AppleTvColors.AppleBlue else AppleTvColors.GlassBorder,
                    shape = AppleTvShapes.Button,
                )
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SearchIcon(
                color = if (inputFocused) AppleTvColors.TextPrimary else AppleTvColors.TextTertiary,
                size = 22.dp,
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = AppleTvColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleTvColors.AppleBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocuser)
                    .onFocusChanged { inputFocused = it.isFocused }
                    // BasicTextField 自己会吞掉 DPAD_DOWN(单行也常吞),用 preview
                    // 在它处理前拦截:DOWN → 跳到"搜索"按钮(主操作)。
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown
                        ) {
                            runCatching { searchButtonFocuser.requestFocus() }
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "输入关键词",
                            color = AppleTvColors.TextTertiary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // 主操作 + 取消
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.focusGroup(),
        ) {
            PrimaryPillButton(
                text = "搜索",
                onClick = { onSearch(query) },
                modifier = Modifier
                    .focusRequester(searchButtonFocuser)
                    .focusProperties {
                        up = inputFocuser
                        right = cancelButtonFocuser
                        down = firstChipFocuser
                    },
            )
            SecondaryPillButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(cancelButtonFocuser)
                    .focusProperties {
                        up = inputFocuser
                        left = searchButtonFocuser
                        down = firstChipFocuser
                    },
            )
        }

        // 热门关键词:无输入时给用户明确的可点方向。
        Spacer(Modifier.height(28.dp))
        Text(
            text = "热门关键词",
            color = AppleTvColors.TextTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (0.5).sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 12.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
        ) {
            items(SEARCH_HOT_KEYWORDS.size) { index ->
                val keyword = SEARCH_HOT_KEYWORDS[index]
                HotKeywordChip(
                    text = keyword,
                    onClick = { onSearch(keyword) },
                    // 第一个 chip 作为"按钮行向下"的落点,并把 UP 路由回搜索按钮。
                    modifier = if (index == 0) {
                        Modifier
                            .focusRequester(firstChipFocuser)
                            .focusProperties { up = searchButtonFocuser }
                    } else {
                        Modifier.focusProperties { up = searchButtonFocuser }
                    },
                )
            }
        }
    }

    // 面板首次进入:焦点落到输入框(用户第一动作就是打字 / 弹出键盘)。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { inputFocuser.requestFocus() }
    }
}

@Composable
private fun HotKeywordChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val textColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.TextSecondary

    Box(
        modifier = modifier
            .requiredHeight(40.dp)
            .clip(AppleTvShapes.Chip)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) AppleTvColors.GlassBorderFocused else AppleTvColors.GlassBorder,
                shape = AppleTvShapes.Chip,
            )
            .tvFocusableLite(
                shape = AppleTvShapes.Chip,
                focusedScale = 1.05f,
                onClick = onClick,
                onFocusChange = { focused = it },
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// =====================================================================
// DetailScreen:详情页 —— 左侧大图 + 右侧文案与播放选项
// =====================================================================

private const val DetailDescriptionCollapsedLines = 5
private const val DetailDialogScrollStepPx = 180

@Composable
private fun DetailScreen(
    details: VideoDetails,
    error: String?,
    onRetry: () -> Unit,
    onPlay: (PlaybackOption) -> Unit,
    onBack: () -> Unit,
) {
    val descriptionText = details.description.ifBlank { "暂无简介" }
    var descriptionDialogVisible by remember(details.id) { mutableStateOf(false) }
    // Back 键:返回上一层(详情 → 主页)。用 BackHandler 注册到 Activity 的
    // OnBackPressedDispatcher,**不依赖 Compose 焦点**,保证焦点在任何子节点
    // (海报、播放按钮…)时 BACK 都能被本屏消费,而不会透传到 Activity.finish
    // 直接退出到系统桌面。enabled 恒为 true:详情屏永远应当拦截 BACK 回主页。
    BackHandler(enabled = true) {
        if (descriptionDialogVisible) {
            descriptionDialogVisible = false
        } else {
            onBack()
        }
    }

    AppleTvBackground {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 72.dp, vertical = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            // 左侧大图
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .height(320.dp)
                    .clip(AppleTvShapes.Card)
                    .background(AppleTvColors.SurfaceElevated),
            ) {
                if (details.thumbnailUrl != null) {
                    AsyncImage(
                        model = details.thumbnailUrl,
                        contentDescription = details.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // P1-5:渐变改为底部向上,只压暗底边以保证标题在海报上可读,
                    // 不再横向压暗右半边破坏海报构图。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.6f to Color.Transparent,
                                    1f to AppleTvColors.DeepSpace.copy(alpha = 0.55f),
                                )
                            ),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = details.title.take(2),
                            color = AppleTvColors.TextTertiary,
                            fontSize = 64.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 右侧文案
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    Spacer(Modifier.height(8.dp))
                    val metadata = listOfNotNull(
                        details.durationSeconds?.let { formatDurationSeconds(it) },
                        details.year,
                        details.creators.firstOrNull(),
                    )
                    if (metadata.isNotEmpty()) {
                        Text(
                            text = metadata.joinToString(" · "),
                            color = AppleTvColors.TextTertiary,
                            fontSize = 14.sp,
                            letterSpacing = (0.6).sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = details.title,
                        color = AppleTvColors.TextPrimary,
                        fontSize = 34.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(20.dp))
                    DetailDescriptionPreview(
                        description = descriptionText,
                        onShowFull = { descriptionDialogVisible = true },
                    )
                }
                Spacer(Modifier.height(24.dp))
                PlaybackOptionsRow(
                    options = details.playbackOptions,
                    onPlay = onPlay,
                )
                if (error != null) {
                    Spacer(Modifier.height(24.dp))
                    DetailErrorBanner(message = error, onRetry = onRetry)
                }
            }
        }
    }

    if (descriptionDialogVisible) {
        DetailDescriptionDialog(
            title = details.title,
            description = descriptionText,
            onDismiss = { descriptionDialogVisible = false },
        )
    }
}

@Composable
private fun DetailDescriptionPreview(
    description: String,
    onShowFull: () -> Unit,
) {
    var hasOverflow by remember(description) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = description,
            color = AppleTvColors.TextSecondary,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            maxLines = DetailDescriptionCollapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                val overflow = result.hasVisualOverflow
                if (hasOverflow != overflow) {
                    hasOverflow = overflow
                }
            },
        )
        if (hasOverflow) {
            Spacer(Modifier.height(12.dp))
            DetailDisclosureButton(onClick = onShowFull)
        }
    }
}

@Composable
private fun DetailDisclosureButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFillStrong
    val contentColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.TextSecondary

    Row(
        modifier = Modifier
            .requiredHeight(42.dp)
            .clip(AppleTvShapes.Button)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) AppleTvColors.GlassBorderFocused else AppleTvColors.GlassBorder,
                shape = AppleTvShapes.Button,
            )
            .tvFocusableLite(
                shape = AppleTvShapes.Button,
                focusedScale = 1.04f,
                onClick = onClick,
                onFocusChange = { focused = it },
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "查看完整简介",
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChevronRightGlyph(color = contentColor, size = 14.dp)
    }
}

@Composable
private fun ChevronRightGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.20f),
            end = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.50f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.50f),
            end = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.80f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun DetailDescriptionDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val closeFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            coroutineScope.launch {
                                scrollState.animateScrollTo(
                                    (scrollState.value + DetailDialogScrollStepPx)
                                        .coerceAtMost(scrollState.maxValue)
                                )
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            coroutineScope.launch {
                                scrollState.animateScrollTo(
                                    (scrollState.value - DetailDialogScrollStepPx)
                                        .coerceAtLeast(0)
                                )
                            }
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 96.dp, vertical = 72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(820.dp)
                    .heightIn(max = 540.dp)
                    .clip(AppleTvShapes.Dialog)
                    .background(
                        Brush.verticalGradient(
                            0f to AppleTvColors.SurfaceElevated,
                            1f to AppleTvColors.DeepSpace,
                        )
                    )
                    .border(1.dp, AppleTvColors.GlassBorder, AppleTvShapes.Dialog)
                    .padding(32.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(AppleTvShapes.Chip)
                        .background(AppleTvColors.AppleBlue),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "完整简介",
                    color = AppleTvColors.AppleBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (0.8).sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = title,
                    color = AppleTvColors.TextPrimary,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "按 ↑↓ 阅读 · BACK 关闭",
                    color = AppleTvColors.TextTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(AppleTvShapes.Card)
                        .background(AppleTvColors.GlassFill)
                        .border(1.dp, AppleTvColors.GlassBorder, AppleTvShapes.Card)
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                    ) {
                        Text(
                            text = description,
                            color = AppleTvColors.TextSecondary,
                            fontSize = 17.sp,
                            lineHeight = 28.sp,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PrimaryPillButton(
                        text = "完成",
                        onClick = onDismiss,
                        horizontalPadding = 34.dp,
                        modifier = Modifier.focusRequester(closeFocusRequester),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppleTvShapes.Button)
            .background(AppleTvColors.GlassFillStrong)
            .border(1.dp, AppleTvColors.GlassBorder, AppleTvShapes.Button)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            color = AppleTvColors.TextPrimary,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SecondaryPillButton(text = "重试", onClick = onRetry)
    }
}

@Composable
private fun PlaybackOptionsRow(
    options: List<PlaybackOption>,
    onPlay: (PlaybackOption) -> Unit,
) {
    if (options.isEmpty()) {
        Text(
            text = "暂无可播放视频",
            color = AppleTvColors.TextTertiary,
            fontSize = 15.sp,
        )
        return
    }
    val playFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playFocusRequester.requestFocus() }
    }
    val optionIds = remember(options) { options.map { it.id } }
    var selectedOptionId by remember(optionIds) { mutableStateOf(options.first().id) }
    val selectedOption = options.firstOrNull { it.id == selectedOptionId } ?: options.first()

    Column {
        PrimaryPillButton(
            text = buildString {
                append("播放")
                selectedOption.label.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            },
            onClick = { onPlay(selectedOption) },
            height = 58.dp,
            horizontalPadding = 38.dp,
            textSize = 20.sp,
            focusedScale = 1.04f,
            leadingIcon = { color -> PlayGlyph(color = color, size = 18.dp) },
            modifier = Modifier.focusRequester(playFocusRequester),
        )

        // 有多档清晰度时,先选择清晰度,再按上方主播放按钮开播。
        if (options.size > 1) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "清晰度",
                color = AppleTvColors.TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (0.5).sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.focusGroup(),
            ) {
                options.take(5).forEach { option ->
                    SelectablePillButton(
                        text = option.label.ifBlank { "播放" },
                        selected = option.id == selectedOption.id,
                        onClick = { selectedOptionId = option.id },
                    )
                }
            }
        }
    }
}

// =====================================================================
// PlayerScreen:全屏播放 + Apple TV 风格自定义控制覆盖层
//
// D-pad 行为(全部由根 Box.onPreviewKeyEvent 统一处理):
//   UP/DOWN    → 上一个/下一个
//   LEFT/RIGHT → seek ±10s(不论覆盖层是否可见)
//   OK         → 切换播放/暂停 + 亮起控制覆盖层
//   BACK        → 根 Surface 拦截返回上一屏
//
// 覆盖层不是可聚焦按钮(纯视觉),电视遥控器可直接操纵播放,不像手机
// 控制条那样需要逐个 Tab 到达按钮。
// =====================================================================

private const val PlaybackUserAgent =
    "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private fun playbackRequestHeaders(url: String): Map<String, String> {
    val headers = mutableMapOf(
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )
    val host = url.toHttpUrlOrNull()?.host ?: return headers
    when {
        host.isHostOrSubdomain("bilivideo.com") || host.isHostOrSubdomain("bilibili.com") ->
            headers["Referer"] = "https://www.bilibili.com"
        host.isHostOrSubdomain("googlevideo.com") || host.isHostOrSubdomain("youtube.com") ->
            headers["Referer"] = "https://www.youtube.com"
    }
    return headers
}

private fun playbackMimeType(mimeType: String?): String {
    return when (mimeType?.lowercase()) {
        "application/dash+xml", "application/mpd", "application/dash" -> MimeTypes.APPLICATION_MPD
        "video/webm" -> MimeTypes.VIDEO_WEBM
        "video/mp4", null -> MimeTypes.VIDEO_MP4
        else -> mimeType
    }
}

private fun String.isHostOrSubdomain(domain: String): Boolean {
    return this == domain || endsWith(".$domain")
}

@Composable
@OptIn(UnstableApi::class)
private fun PlayerScreen(
    stream: PlaybackOption,
    hasPrevious: Boolean,
    previousTitle: String?,
    hasNext: Boolean,
    nextTitle: String?,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playbackUrl = remember(stream.url) { stream.url }

    val player = remember(playbackUrl, stream.mimeType) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(PlaybackUserAgent)
            .setDefaultRequestProperties(playbackRequestHeaders(playbackUrl))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(playbackUrl)
                        .setMimeType(playbackMimeType(stream.mimeType))
                        .build(),
                )
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
    }

    // 播放器状态(由 Player.Listener 推送更新)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    val currentHasNext by rememberUpdatedState(hasNext)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnPlayPrevious by rememberUpdatedState(onPlayPrevious)
    val currentOnBack by rememberUpdatedState(onBack)

    // 控制覆盖层自动闪烁:每次用户按键亮起,3 秒后淡出
    var controlsVisible by remember { mutableStateOf(true) }
    val playerFocusRequester = remember { FocusRequester() }

    fun showControls() {
        controlsVisible = false
        coroutineScope.launch {
            delay(16)
            controlsVisible = true
        }
    }

    // 启动覆盖层自动隐藏倒计时
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0)
                }
                // 播放到结尾自动续播下一个(若有)
                if (state == Player.STATE_ENDED && currentHasNext) {
                    currentOnPlayNext()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 生命周期感知:app 退到后台(按 HOME / 切到别的应用 → ON_STOP)时退出播放页。
    // 只 pause 不够:Composition 可能仍保留,remember 的 ExoPlayer 会继续持有
    // Media3/native decoder/buffer 资源。这里触发 onBack 让 PlayerScreen 离开组合,
    // 复用上方 onDispose 统一 removeListener + release。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                currentOnBack()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 当覆盖层可见时,每 300ms 刷新进度条位置
    LaunchedEffect(controlsVisible) {
        while (controlsVisible) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(300)
        }
    }

    LaunchedEffect(Unit) { player.play() }
    LaunchedEffect(playerFocusRequester) {
        withFrameNanos { }
        playerFocusRequester.requestFocus()
    }
    // Back 键:返回上一层(播放 → 详情)。用 BackHandler 注册到 Activity 的
    // OnBackPressedDispatcher,**不依赖 Compose 焦点**。这点很关键:播放屏的
    // BACK 之前既没在根 Surface 处理、根 Box.onPreviewKeyEvent 也不处理 BACK,
    // 导致事件透传到 Activity.finish → 直接退出到系统桌面。改用 BackHandler 后,
    // 只要本屏在前台就稳定消费 BACK,逐层回退(播放 → 详情 → 主页)。
    BackHandler(enabled = true) { onBack() }

    fun togglePlaybackAndShowControls() {
        if (isPlaying) player.pause() else player.play()
        showControls()
    }

    // 根 Box 处理所有 D-pad / 确认键,不交给 PlayerView 内部控制条
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(playerFocusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val nativeCode = event.nativeKeyEvent?.keyCode
                val isOk = nativeCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || nativeCode == android.view.KeyEvent.KEYCODE_ENTER
                val isNextKey = nativeCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                val isPreviousKey = nativeCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                when {
                    isOk -> {
                        togglePlaybackAndShowControls()
                        true
                    }
                    // ↑ 或 媒体上一首键 → 播放当前分类的上一个视频
                    event.key == Key.DirectionUp || isPreviousKey -> {
                        if (hasPrevious) {
                            currentOnPlayPrevious()
                        } else {
                            showControls()
                        }
                        true
                    }
                    // ↓ 或 媒体下一首键 → 播放当前分类的下一个视频
                    event.key == Key.DirectionDown || isNextKey -> {
                        if (hasNext) {
                            currentOnPlayNext()
                        } else {
                            showControls()
                        }
                        true
                    }
                    event.key == Key.DirectionLeft -> {
                        player.seekBack(); showControls(); true
                    }
                    event.key == Key.DirectionRight -> {
                        player.seekForward(); showControls(); true
                    }
                    else -> false
                }
            }
            .focusable()
            .background(Color.Black),
    ) {
        // 视频层(不用 Media3 内建手机控制条,我们用 Compose 覆盖层代替)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
            update = { it.player = player },
        )

        // Apple TV 风格控制覆盖层:顶部遮罩 + 中间大按钮 + 底部进度条
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(400)),
        ) {
            PlayerOverlay(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                title = stream.label,
                hasPrevious = hasPrevious,
                previousTitle = previousTitle,
                hasNext = hasNext,
                nextTitle = nextTitle,
            )
        }
    }
}

// =====================================================================
// PlayerOverlay:纯视觉覆盖层 — 顶部渐变 / 中间大按钮 / 底部进度条 + 时间
// =====================================================================

@Composable
private fun PlayerOverlay(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    title: String,
    hasPrevious: Boolean,
    previousTitle: String?,
    hasNext: Boolean,
    nextTitle: String?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // ── 顶部暗化渐变 + 当前清晰度标题 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        1f to Color.Transparent,
                    )
                )
                .padding(horizontal = 56.dp)
                .padding(top = 28.dp, bottom = 48.dp),
        ) {
            Text(
                text = "正在播放 · $title",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── 中间大圆形 播放/暂停 指示(纯视觉) ──
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                PauseGlyph(color = Color.White, size = 36.dp)
            } else {
                PlayGlyph(color = Color.White, size = 36.dp)
            }
        }

        // ── 底部渐变遮罩 + 进度条 + 时间码 + 遥控提示 ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
                .padding(horizontal = 56.dp)
                .padding(bottom = 28.dp),
        ) {
            val fraction = if (duration > 0)
                (position.toFloat() / duration).coerceIn(0f, 1f)
            else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(AppleTvColors.AppleBlue),
                )
            }

            Spacer(Modifier.height(12.dp))

            // 时间码 + 遥控操作提示同排,左右分布,避免文字相互覆盖
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("OK 播放/暂停 · ←→ 快退/快进 10 秒")
                        if (hasPrevious) append(" · ↑ 上一个")
                        if (hasNext) append(" · ↓ 下一个")
                    },
                    color = AppleTvColors.TextTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "${formatTime(position)} / ${formatTime(duration)}",
                    color = AppleTvColors.TextSecondary,
                    fontSize = 15.sp,
                )
            }

            // 上/下一个视频预告(若有),单独一行,留足间距防止覆盖
            if (hasPrevious && previousTitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "上一个:$previousTitle",
                    color = AppleTvColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasNext && nextTitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "下一个:$nextTitle",
                    color = AppleTvColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 毫秒 → "mm:ss" 或 "h:mm:ss"。 */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val sec = total % 60
    val min = (total / 60) % 60
    val hr = total / 3600
    return if (hr > 0)
        String.format("%d:%02d:%02d", hr, min, sec)
    else
        String.format("%d:%02d", min, sec)
}

/** 秒 → "mm:ss" 或 "h:mm:ss"。 */
private fun formatDurationSeconds(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val sec = total % 60
    val min = (total / 60) % 60
    val hr = total / 3600
    return if (hr > 0)
        String.format("%d:%02d:%02d", hr, min, sec)
    else
        String.format("%d:%02d", min, sec)
}
