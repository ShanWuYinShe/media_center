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
import androidx.compose.ui.focus.focusRequester
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
import com.example.mediacenter.ui.components.SecondaryPillButton
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
    var searchFocused by remember { mutableStateOf(false) }
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
            searchFocused = false
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
            searchFocused = false
            restoreAttempted = true
        }
    }

    // SearchPanel 打开时,Back 优先关闭面板而不是退出 app
    BackHandler(enabled = searchVisible) { searchVisible = false }

    AppleTvBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // 分类区域:搜索按钮 + 分类条,紧凑排列节省纵向空间
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryPillButton(
                    text = "搜索",
                    onClick = { searchVisible = !searchVisible },
                    focusedOverride = searchFocused,
                    onFocusChange = { searchFocused = it },
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
                        // 焦点离开搜索按钮:强制清除其焦点态,避免嵌套 focusGroup
                        // 偶发丢失 blur 事件导致搜索按钮“常亮”。
                        searchFocused = false
                        homeFocusState.markCategoryFocused(category.id)
                    },
                )
            }

            // 搜索框(默认收起,点击搜索按钮展开)
            if (searchVisible) {
                SearchPanel(
                    initialQuery = state.query,
                    onSearch = { keyword ->
                        onSearch(keyword)
                        searchVisible = false
                    },
                    onDismiss = { searchVisible = false },
                )
            }

            // 内容区:海报网格 / 加载 / 错误 / 空态
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
                            searchFocused = false
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

// =====================================================================
// SearchPanel:展开式搜索面板(玻璃卡 + 文本框 + 两个胶囊按钮)
// =====================================================================

@Composable
private fun SearchPanel(
    initialQuery: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppleTvShapes.Card)
                .background(AppleTvColors.GlassFillStrong)
                .padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Column {
                Text(
                    text = "关键词",
                    color = AppleTvColors.TextTertiary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (0.5).sp,
                )
                Spacer(Modifier.height(8.dp))
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
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "可选关键词(留空使用分类默认词)",
                                color = AppleTvColors.TextTertiary,
                                fontSize = 22.sp,
                            )
                        }
                        inner()
                    }
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrimaryPillButton(text = "搜索", onClick = { onSearch(query) })
                    SecondaryPillButton(text = "取消", onClick = onDismiss)
                }
            }
        }
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
