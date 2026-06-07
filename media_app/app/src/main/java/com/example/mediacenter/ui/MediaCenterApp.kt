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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
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
import com.example.mediacenter.ui.components.PosterGrid
import com.example.mediacenter.ui.components.PrimaryPillButton
import com.example.mediacenter.ui.components.SearchModeTopBar
import com.example.mediacenter.ui.components.SecondaryPillButton
import com.example.mediacenter.ui.components.tvFocusableLite
import com.example.mediacenter.ui.theme.AppleTvColors
import com.example.mediacenter.ui.theme.AppleTvShapes
import com.example.mediacenter.ui.theme.AppleTvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val homeFocusState = rememberSaveable(saver = HomeFocusStateSaver) { HomeFocusState() }
    val categoryRailState = rememberLazyListState()
    val posterGridState = rememberLazyGridState()

    AppleTvTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                        return@onPreviewKeyEvent false
                    }
                    // 关键修复:仅在 Player/Detail 屏幕拦截 Back,Home 屏幕放行,
                    // 让事件冒泡到 OnBackPressedDispatcher → Activity.finish()。
                    if (state.playing != null || state.selected != null) {
                        onBack()
                        true
                    } else {
                        false
                    }
                },
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
                    PlayerScreen(
                        stream = state.playing,
                        hasNext = nextVideo != null || state.canLoadMore,
                        nextTitle = nextVideo?.title,
                        onPlayNext = onPlayNext,
                        onBack = onBack,
                    )
                }
                state.selected != null -> DetailScreen(
                    details = state.selected,
                    onPlay = onPlay,
                    onBack = onBack,
                )
                else -> HomeScreen(
                    state = state,
                    onSearch = onSearch,
                    onSelectCategory = onSelectCategory,
                    onSelect = onSelect,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
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
    onRetry: () -> Unit,
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

    // Back 键分两个独立 BackHandler(后注册先调用,但 enabled 互斥所以不会同时拦截):
    //   1) searchVisible → 关 SearchPanel(回到分类浏览);不放行 Back 走 Activity.finish。
    //   2) inSearchMode(已有结果)→ 退出整个搜索模式(回到 first 分类)。
    //   3) 否则让 Back 走默认 Activity.finish()。
    BackHandler(enabled = searchVisible) { closeSearch() }
    BackHandler(enabled = inSearchMode) { exitSearchMode() }

    AppleTvBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏分三态(全部跟内容区语义对齐):
            //   1) searchVisible=true            → 只显示 SearchPill;
            //      **分类条完全隐藏**,因为内容区是 SearchPanel,跟分类无关,
            //      之前显示分类条 + selected 蓝底是误导。
            //   2) inSearchMode=true(已搜过)    → SearchModeTopBar(关键词 + ×)
            //   3) 默认(分类浏览)                → SearchPill + CategoryRail
            if (searchVisible) {
                // 只留"搜索"按钮作为顶栏元素,水平居左;分类条让位给 SearchPanel。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryPillButton(
                        text = "搜索",
                        onClick = { /* 已在搜索面板,忽略 */ },
                        onFocused = { /* 已在搜索面板 */ },
                    )
                }
            } else if (inSearchMode) {
                SearchModeTopBar(
                    query = state.query,
                    onClear = { exitSearchMode() },
                    // 点搜索栏或按 OK → 重新展开 SearchPanel 继续编辑 query,
                    // 跟 iOS / tvOS 搜索栏行为一致;否则搜完就只能 × 退出,体验生硬。
                    onTapQuery = { searchVisible = true },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryPillButton(
                        text = "搜索",
                        // 选中(获得焦点)即视为确认:直接展开搜索页,无需再按 OK。
                        onClick = { searchVisible = true },
                        onFocused = { searchVisible = true },
                    )
                    Spacer(Modifier.width(12.dp))
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
                        state.items.isEmpty() -> EmptyState(message = "当前分类暂无内容")
                        else -> PosterGrid(
                            items = state.items,
                            onSelect = onSelect,
                            canLoadMore = state.canLoadMore,
                            loadingMore = state.loadingMore,
                            onLoadMore = onLoadMore,
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
                            onUpFromFirstRow = {
                                state.selectedCategoryId?.let { id ->
                                    homeFocusState.markCategoryFocused(id)
                                    categoryFocusers[id]?.let { fr ->
                                        focusScope.launch {
                                            withFrameNanos { }
                                            runCatching { fr.requestFocus() }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchPanel(
    initialQuery: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }

    val searchButtonFocuser = remember { FocusRequester() }
    val cancelButtonFocuser = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 12.dp),
    ) {
        // 标题:清楚说明当前在搜索模式
        Text(
            text = "搜索",
            color = AppleTvColors.TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppleTvShapes.Card)
                .background(AppleTvColors.GlassFillStrong)
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column {
                // 输入框区域:大号、明确提示文字
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AppleTvColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleTvColors.AppleBlue),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "输入关键词,或从下方选一个",
                                color = AppleTvColors.TextTertiary,
                                fontSize = 24.sp,
                            )
                        }
                        inner()
                    }
                )
                // 焦点接续节点:BasicTextField 把 DOWN 吞了,这里放 hidden
                // focusable spacer 把 DOWN 焦点路由回"搜索"按钮(主操作)。
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .focusable()
                        .focusProperties { down = searchButtonFocuser },
                )
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
                                left = cancelButtonFocuser
                                right = cancelButtonFocuser
                            },
                    )
                    SecondaryPillButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(cancelButtonFocuser)
                            .focusProperties {
                                left = searchButtonFocuser
                                right = searchButtonFocuser
                            },
                    )
                }
            }
        }

        // 热门关键词 chip 入口:无输入时让用户有可点的方向,不至于面对
        // 空输入框。点击 = 立即用该关键词搜(直接走 onSearch + onDismiss
        // 同 closeSearch 路径)。
        Spacer(Modifier.height(24.dp))
        Text(
            text = "热门关键词",
            color = AppleTvColors.TextTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (0.5).sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        // chip 用 flow layout 不容易做(没现成组件),退而用 LazyRow 横向滚动,
        // 配合 focusGroup + focusRequester 让横向焦点跳转顺畅。
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
                )
            }
        }
    }

    // 面板首次进入(从无到有):焦点落到"搜索"按钮(主操作),用户第一动作按 OK 即搜。
    // 已经在编辑中(同一面板 key 复用)时不重复抢焦点,避免打断 IME 输入。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { searchButtonFocuser.requestFocus() }
    }
}

@Composable
private fun HotKeywordChip(
    text: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val textColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.TextSecondary

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(AppleTvShapes.Chip)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (focused) Color.Transparent else AppleTvColors.GlassBorder,
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
        )
    }
}

// =====================================================================
// DetailScreen:详情页 —— 左侧大图 + 右侧文案与播放选项
// =====================================================================

@Composable
private fun DetailScreen(
    details: VideoDetails,
    onPlay: (PlaybackOption) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true) { onBack() }

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
                Text(
                    text = details.description.ifBlank { "暂无简介" },
                    color = AppleTvColors.TextSecondary,
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(32.dp))
                PlaybackOptionsRow(
                    options = details.playbackOptions,
                    onPlay = onPlay,
                )
            }
        }
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

    Column {
        // 主播放按钮:默认用第一档(最高)清晰度直接播放,初始焦点落在此
        val primary = options.first()
        PrimaryPillButton(
            text = "▶  播放",
            onClick = { onPlay(primary) },
            modifier = Modifier.focusRequester(playFocusRequester),
        )

        // 有多档清晰度时,展示清晰度选择行(每个都可聚焦)
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
                    SecondaryPillButton(
                        text = option.label.ifBlank { "播放" },
                        onClick = { onPlay(option) },
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
//   LEFT/RIGHT → seek ±10s(不论覆盖层是否可见)
//   UP/DOWN/OK  → 切换播放/暂停 + 亮起控制覆盖层
//   BACK        → 根 Surface 拦截返回上一屏
//
// 覆盖层不是可聚焦按钮(纯视觉),电视遥控器可直接操纵播放,不像手机
// 控制条那样需要逐个 Tab 到达按钮。
// =====================================================================

@Composable
@OptIn(UnstableApi::class)
private fun PlayerScreen(
    stream: PlaybackOption,
    hasNext: Boolean,
    nextTitle: String?,
    onPlayNext: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val player = remember(stream.url) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(stream.url)
                        .setMimeType(
                            if (stream.mimeType == "video/webm") MimeTypes.VIDEO_WEBM
                            else MimeTypes.VIDEO_MP4
                        )
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
                if (state == Player.STATE_ENDED && hasNext) {
                    onPlayNext()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
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
                when {
                    isOk || event.key == Key.DirectionUp -> {
                        togglePlaybackAndShowControls()
                        true
                    }
                    // ↓ 或 媒体下一首键 → 播放当前分类的下一个视频
                    event.key == Key.DirectionDown || isNextKey -> {
                        if (hasNext) {
                            onPlayNext()
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
            Text(
                text = if (isPlaying) "⏸" else "▶",
                color = Color.White,
                fontSize = 36.sp,
            )
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
                        append("OK 播放/暂停 · ←→ 快退/快进")
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

            // 下一个视频预告(若有),单独一行,留足间距防止覆盖
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
