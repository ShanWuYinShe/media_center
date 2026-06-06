package com.example.mediacenter.ui.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleTvColors
import com.example.mediacenter.ui.theme.AppleTvShapes

private const val FocusLogTag = "MediaCenterFocus"

internal suspend fun requestFocusWhenReady(
    focusRequester: FocusRequester,
    targetDescription: String,
) {
    repeat(3) { attempt ->
        withFrameNanos { }
        try {
            focusRequester.requestFocus()
            return
        } catch (error: IllegalStateException) {
            if (attempt == 2) {
                Log.w(FocusLogTag, "Unable to restore focus to $targetDescription", error)
            }
        }
    }
}

// =====================================================================
// 顶栏:类 Apple TV+ 的"大标题 + 胶囊搜索按钮"
// =====================================================================

@Composable
fun AppleTvTopBar(
    title: String,
    subtitle: String? = null,
    onSearchClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 顶栏 padding 从 28dp 缩到 16dp,让"标题 + 副标题"区块从 ~140px
            // 缩到 ~100px,为下方海报 + 标题区域腾出 40px 空间。
            .padding(horizontal = 56.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 36.sp,  // 从 42sp 缩到 36sp,跟 sub 字号差距更合理
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
                color = AppleTvColors.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 14.sp,  // 从 16sp 缩到 14sp
                    color = AppleTvColors.TextTertiary,
                    maxLines = 1,    // 强制单行,避免副标题过长把分类条挤走
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SearchPill(onClick = onSearchClick)
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) Color.White else AppleTvColors.GlassFill
    val textColor = if (focused) AppleTvColors.DeepSpace else AppleTvColors.TextPrimary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(54.dp)
            .clip(AppleTvShapes.Chip)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (focused) Color.Transparent else AppleTvColors.GlassBorder,
                shape = AppleTvShapes.Chip,
            )
            .tvFocusableLite(
                shape = AppleTvShapes.Chip,
                focusedScale = 1.06f,
                onClick = onClick,
                onFocusChange = { focused = it },
            )
            .padding(horizontal = 24.dp),
    ) {
        // SF-style 放大镜图标(用 Canvas 简画一个圆 + 把手)
        SearchIcon(color = textColor, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "搜索",
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SearchIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val r = this.size.minDimension * 0.32f
        val cx = this.size.width * 0.42f
        val cy = this.size.height * 0.42f
        drawCircle(
            color = color,
            radius = r,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(cx + r * 0.72f, cy + r * 0.72f),
            end = androidx.compose.ui.geometry.Offset(this.size.width * 0.92f, this.size.height * 0.92f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

// =====================================================================
// CategoryChip:胶囊式分类选择条(替代原来的大卡片)
// =====================================================================

@Composable
fun CategoryChip(
    category: ContentCategory,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 关键:与 `tvFocusableLite` 共享同一个 interactionSource,这样
    // 内部 `collectIsFocusedAsState()` 跟外部读到的值严格一致。
    // 之前的实现外层 `var focused by remember` 跟内部不同源 → 状态不同步。
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    // 视觉规则(对照 Apple Music tab 真实行为):
    //   焦点态要**强于** selected,因为焦点代表"用户当前正在操作的位置",
    //   selected 是"被动标记"哪个是当前分类。
    //   - 焦点 + selected  →  Apple Blue 实心 + scale 1.10(顶配:已有颜色 + 抬升)
    //   - 焦点 + 未选中  →  **白底反白** + scale 1.10(颜色突变,强提示)
    //   - 失焦 + selected  →  Apple Blue(无缩放,只是"我是当前分类"标记)
    //   - 失焦 + 未选中  →  玻璃灰
    val bg = when {
        focused && !selected -> Color.White
        selected -> AppleTvColors.AppleBlue
        else -> AppleTvColors.GlassFill
    }
    val textColor = when {
        focused && !selected -> AppleTvColors.DeepSpace
        selected -> AppleTvColors.TextPrimary
        else -> AppleTvColors.TextSecondary
    }
    // 焦点视觉规则(全 app 统一 — 任何 chip 焦点态视觉一致):
    //   - 不再使用 scale/elevation 区分(selected 跟未选中),只靠:
    //       · 白边 2.5dp (焦点永远有,失焦 0)
    //       · 底色 (焦点+未选中 → 白底反白,焦点+selected → Apple Blue,失焦未选 → 玻璃灰,失焦 selected → Apple Blue)
    //   - 焦点 + selected 跟 焦点 + 未选中 视觉规则相同:都有白边,只是底色不同。
    //   - 这避免了"国产电影 selected + 焦点"看起来"永远焦点"问题,
    //     因为它跟其他失焦 chip **大小一致**,只颜色不同。
    val focusedScale = 1.0f
    val focusedElevation = 0.dp
    val borderColor by animateColorAsState(
        targetValue = if (focused) AppleTvColors.GlassBorderFocused else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "chipFocusBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 2.5.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "chipFocusBorderWidth",
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(AppleTvShapes.Chip)
            .background(bg)
            .border(width = borderWidth, color = borderColor, shape = AppleTvShapes.Chip)
            .tvFocusableLite(
                shape = AppleTvShapes.Chip,
                focusedScale = focusedScale,
                interactionSource = interactionSource,
                onClick = onClick,
                onFocusChange = { isFocused ->
                    if (isFocused) {
                        onFocused()
                        // 焦点到达时自动切换分类，不需要再按确认
                        if (!selected) onClick()
                    }
                },
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = category.title,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = (-0.1).sp,
        )
        // 已选中的 chip:始终在底部显示 Apple Blue 指示条
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.55f)
                    .height(3.dp)
                    .clip(AppleTvShapes.Chip)
                    .background(AppleTvColors.AppleBlue),
            )
        }
    }
}

@Composable
fun CategoryRail(
    categories: List<ContentCategory>,
    selectedCategoryId: String?,
    onSelectCategory: (ContentCategory) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 56.dp),
    state: LazyListState = rememberLazyListState(),
    pendingFocusCategoryId: String? = null,
    onPendingFocusHandled: (String) -> Unit = {},
    onCategoryFocused: (ContentCategory) -> Unit = {},
    // 外部可向 categoryId 注入 FocusRequester,这样 HomeScreen 在
    // onUpFromPoster 回调里能直接用 selectedCategoryId 找 FocusRequester
    // 请求焦点,绕过 LazyRow 默认的 focusRestorer 行为。
    focuserRegister: ((String, FocusRequester) -> Unit)? = null,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxWidth().focusGroup(),
        state = state,
    ) {
        items(categories, key = { it.id }) { category ->
            val focusRequester = remember { FocusRequester() }
            // 把 FocusRequester 暴露给外部
            if (focuserRegister != null) {
                LaunchedEffect(focusRequester) { focuserRegister(category.id, focusRequester) }
            }
            LaunchedEffect(pendingFocusCategoryId, category.id) {
                if (pendingFocusCategoryId == category.id) {
                    requestFocusWhenReady(focusRequester, "category ${category.id}")
                    onPendingFocusHandled(category.id)
                }
            }
            CategoryChip(
                category = category,
                selected = category.id == selectedCategoryId,
                onClick = { onSelectCategory(category) },
                onFocused = { onCategoryFocused(category) },
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}

// =====================================================================
// PosterCard:海报卡 + 标题叠加 + 焦点抬升 + 顶部高光
// =====================================================================

@Composable
fun PosterCard(
    video: VideoSummary,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val focusedScale = 1.08f
    val cardColor by animateColorAsState(
        targetValue = if (focused) AppleTvColors.GlassBorderFocused
                      else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "posterFocusBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 3.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "posterFocusBorderWidth",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "posterFocusIndicator",
    )

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = focusModifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(AppleTvShapes.Poster)
                .background(AppleTvColors.SurfaceElevated)
                .border(width = borderWidth, color = cardColor, shape = AppleTvShapes.Poster)
                .tvFocusable(
                    shape = AppleTvShapes.Poster,
                    focusedScale = focusedScale,
                    interactionSource = interactionSource,
                    onClick = onClick,
                    onFocusChange = { isFocused ->
                        focused = isFocused
                        if (isFocused) onFocused()
                    },
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (video.thumbnailUrl != null) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PosterPlaceholder(title = video.title)
            }
            // 底部渐变:让标题文字在海报上可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xDD000000),
                        ),
                    )
            )
            // 焦点态 Apple Blue 指示条
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        AppleTvColors.AppleBlue.copy(alpha = indicatorAlpha),
                    ),
            )
            // 底部标题叠加
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = if (focused) 14.sp else 13.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    video.durationSeconds?.let { formatDurationSeconds(it) },
                    video.year,
                    video.creators.firstOrNull(),
                )
                if (metadata.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = metadata.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 顶部 spec highlight:焦点态变亮,实现"被点亮"质感
            SpecHighlight(shape = AppleTvShapes.Poster, isFocused = focused)
        }
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1C1C24), Color(0xFF0E0E14)),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2),
            color = AppleTvColors.TextTertiary,
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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

// =====================================================================
// 海报横向轨道
// =====================================================================

@Composable
fun PosterRail(
    items: List<VideoSummary>,
    onSelect: (VideoSummary) -> Unit,
    state: LazyListState = rememberLazyListState(),
    pendingFocusVideoId: String? = null,
    onPendingFocusHandled: (String) -> Unit = {},
    onVideoFocused: (VideoSummary) -> Unit = {},
    onUpFromPoster: (() -> Unit)? = null,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 80.dp, vertical = 32.dp),
        // 顶层 Box 包一层 onPreviewKeyEvent:在 LazyRow 内按 UP 时,通知 HomeScreen
        // 把焦点回退到当前 selected 的分类 chip,而不是停留在原 LazyRow 的
        // focusRestorer 恢复的"上次海报"位置。
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionUp) {
                    if (onUpFromPoster != null) {
                        onUpFromPoster.invoke()
                        true
                    } else false
                } else false
            },
        state = state,
    ) {
        items(items, key = { it.id }) { video ->
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(pendingFocusVideoId, video.id) {
                if (pendingFocusVideoId == video.id) {
                    requestFocusWhenReady(focusRequester, "video ${video.id}")
                    onPendingFocusHandled(video.id)
                }
            }
            PosterCard(
                video = video,
                // 海报宽度 210→180dp:2:3 = 270dp = 432px 高,留 ~140px 标题区
                // 给"19sp × 2 行 + 14spacer + 13sp × 1 行"全部可见。
                modifier = Modifier.width(180.dp),
                onClick = { onSelect(video) },
                onFocused = { onVideoFocused(video) },
                focusModifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}

// =====================================================================
// PosterGrid:网格布局海报墙(替代单行 rail,同屏显示更多内容)
// =====================================================================

@Composable
fun PosterGrid(
    items: List<VideoSummary>,
    onSelect: (VideoSummary) -> Unit,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    pendingFocusVideoId: String? = null,
    onPendingFocusHandled: (String) -> Unit = {},
    onVideoFocused: (VideoSummary) -> Unit = {},
    onUpFromFirstRow: (() -> Unit)? = null,
) {
    // 追踪当前焦点所在 item 的 index,用于判断"第一行按 UP"场景
    var focusedIndex by remember { mutableStateOf(-1) }
    val shouldLoadMore by remember(items.size, canLoadMore, loadingMore, state) {
        derivedStateOf {
            val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val userHasScrolled = state.firstVisibleItemIndex > 0 ||
                state.firstVisibleItemScrollOffset > 0
            canLoadMore &&
                !loadingMore &&
                userHasScrolled &&
                items.isNotEmpty() &&
                lastVisible >= items.lastIndex - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 130.dp),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
                    return@onPreviewKeyEvent false
                }
                if (onUpFromFirstRow == null || focusedIndex < 0) {
                    return@onPreviewKeyEvent false
                }
                // 根据可见 items 计算列数,判断焦点是否在第一行
                val visibleItems = state.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) return@onPreviewKeyEvent false
                val minY = visibleItems.minOf { it.offset.y }
                val columnCount = visibleItems.count { it.offset.y == minY }
                if (focusedIndex < columnCount) {
                    onUpFromFirstRow()
                    true
                } else {
                    false
                }
            },
        state = state,
    ) {
        items(count = items.size, key = { items[it].id }) { index ->
            val video = items[index]
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(pendingFocusVideoId, video.id) {
                if (pendingFocusVideoId == video.id) {
                    requestFocusWhenReady(focusRequester, "video ${video.id}")
                    onPendingFocusHandled(video.id)
                }
            }
            PosterCard(
                video = video,
                onClick = { onSelect(video) },
                onFocused = {
                    focusedIndex = index
                    onVideoFocused(video)
                },
                focusModifier = Modifier.focusRequester(focusRequester),
            )
        }
        if (loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                )
            }
        }
    }
}

// =====================================================================
// 错误态:tvOS 风格 — 居中 icon + 标题 + 副标题 + 胶囊按钮
// =====================================================================

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ⚠️ tvOS 风格"信号灯"—— 不用 emoji,用 Canvas 画一个简洁的环 + 感叹号
        WarningGlyph(size = 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "暂时无法加载",
            color = AppleTvColors.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AppleTvColors.TextTertiary,
            fontSize = 15.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton(text = "重试", onClick = onRetry)
    }
}

@Composable
private fun WarningGlyph(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AppleTvColors.GlassFill)
            .border(1.dp, AppleTvColors.GlassBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            color = AppleTvColors.TextDanger,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// =====================================================================
// PrimaryPillButton:胶囊形主按钮(Apple Blue 实心,焦点变白)
// =====================================================================

@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) Color.White else AppleTvColors.AppleBlue
    val textColor = if (focused) AppleTvColors.AppleBlue else AppleTvColors.TextPrimary

    Box(
        modifier = modifier
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(AppleTvShapes.Button)
            .background(bg)
            .tvFocusableLite(
                shape = AppleTvShapes.Button,
                focusedScale = 1.06f,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 次级胶囊按钮(透明 + 边框,焦点态蓝色填充)。
 *
 * 默认自管理焦点态。也支持“受控模式”:父级传入 [focusedOverride] 强制焦点态,
 * 并通过 [onFocusChange] 监听焦点变化。受控模式用于解决嵌套 focusGroup 环境下
 * `onFocusChanged(false)` 偶发丢失导致按钮“常亮”的问题——父级可在焦点落到
 * 其它元素时强制把它置为非焦点。
 */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedOverride: Boolean? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    var selfFocused by remember { mutableStateOf(false) }
    val focused = focusedOverride ?: selfFocused
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val textColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.TextSecondary
    val borderColor = if (focused) Color.Transparent else AppleTvColors.GlassBorder

    Box(
        modifier = modifier
            .height(48.dp)
            .onFocusChanged {
                selfFocused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .clip(AppleTvShapes.Button)
            .background(bg)
            .border(1.dp, borderColor, AppleTvShapes.Button)
            .tvFocusableLite(
                shape = AppleTvShapes.Button,
                focusedScale = 1.06f,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// =====================================================================
// 加载态
// =====================================================================

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = AppleTvColors.AppleBlue,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(40.dp),
        )
    }
}

// =====================================================================
// 空态(分类下没有结果时)
// =====================================================================

@Composable
fun EmptyState(message: String = "暂无内容") {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = AppleTvColors.TextTertiary,
            fontSize = 17.sp,
        )
    }
}

// =====================================================================
// 背景:Apple TV+ 顶部羽化渐变
// =====================================================================

@Composable
fun AppleTvBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to AppleTvColors.HeroGradientTop,
                    0.55f to AppleTvColors.SurfaceBase,
                    1f to AppleTvColors.DeepSpace,
                )
            ),
    ) {
        content()
    }
}
