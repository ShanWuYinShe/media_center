package com.example.mediacenter.ui.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
fun SearchIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
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
// SearchModeTopBar:搜索模式顶栏(替代分类条)—— 放大镜 + 关键词 + 清除按钮
//
// 出现条件:HomeScreen 处于"搜索模式",即
//   - 搜索面板展开(用户正在输入),或
//   - 已完成搜索、内容区是搜索结果(state.query 非空)。
// 在这两种情况下都不再展示分类条,避免"分类条 selected 蓝底 + 内容是搜索结果"
// 的语义冲突(原 Issue:选择搜索以后,视频分类的焦点选择效果还显示)。
//
// 交互:整条"搜索栏"是单一可聚焦 + 可点击对象(放大镜 + 关键词作为一个整体),
// 按 OK 或点它都能展开 SearchPanel 继续编辑 query;右侧 × 单独可聚焦 +
// 可点击,用来退出整个搜索模式。两者的视觉权重跟 tvOS 搜索栏一致。
// =====================================================================

@Composable
fun SearchModeTopBar(
    query: String,
    onClear: () -> Unit,
    onTapQuery: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整条"搜索栏"作为单一可聚焦 + 可点击对象:点击或 OK 都展开 SearchPanel。
        // 用 tvFocusableLite 替代 clickable + focusable(后者在 TV 模拟器上对
        // input tap 路由不可靠;tvFocusableLite 内部的 clickable + scale + shadow
        // 跟 ClearButton 一致,遥控器 OK 直接触发 onClick)。
        var focused by remember { mutableStateOf(false) }
        val borderColor by animateColorAsState(
            targetValue = if (focused) AppleTvColors.GlassBorderFocused
                          else AppleTvColors.GlassBorder,
            animationSpec = tween(durationMillis = 180),
            label = "searchBarFocusBorder",
        )
        val bg = if (focused) AppleTvColors.GlassFillStrong else AppleTvColors.GlassFill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .clip(AppleTvShapes.Chip)
                .background(bg)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = borderColor,
                    shape = AppleTvShapes.Chip,
                )
                .tvFocusableLite(
                    shape = AppleTvShapes.Chip,
                    focusedScale = 1.04f,
                    onClick = onTapQuery,
                    onFocusChange = { focused = it },
                )
                .padding(horizontal = 22.dp),
        ) {
            SearchIcon(
                color = if (focused) AppleTvColors.TextPrimary
                        else AppleTvColors.TextSecondary,
                size = 20.dp,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = query.ifEmpty { "搜索…" },
                color = if (query.isEmpty()) AppleTvColors.TextTertiary
                        else AppleTvColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(12.dp))
        // 清除按钮:tvOS 风格 × 圆形按钮,焦点态变 Apple Blue 实底。
        ClearButton(onClick = onClear)
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val glyphColor = if (focused) AppleTvColors.TextPrimary
                     else AppleTvColors.TextSecondary

    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(AppleTvShapes.Chip)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (focused) Color.Transparent else AppleTvColors.GlassBorder,
                shape = AppleTvShapes.Chip,
            )
            .tvFocusableLite(
                shape = AppleTvShapes.Chip,
                focusedScale = 1.08f,
                onClick = onClick,
                onFocusChange = { focused = it },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ClearGlyph(color = glyphColor, size = 18.dp)
    }
}

@Composable
private fun ClearGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.8.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        // × 用两条对角线
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.22f),
            end = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.78f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.22f),
            end = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.78f),
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
    // 焦点态由 `tvFocusableLite` 的 onFocusChange 回调驱动(回调内部用
    // onFocusChanged 直接读节点真实焦点态,可靠不卡顿)。不再用
    // collectIsFocusedAsState —— 它在 composed 重新物化时可能残留 focused。
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
    // 边框三态:
    //   · 焦点               → 近白 halo 边(2.5dp),最强,代表"正在操作"
    //   · 失焦 + selected    → Apple Blue 描边(1.5dp),持续标记"当前分类",
    //                          这样焦点移到视频列表后,用户一眼能看出在看哪个分类
    //   · 失焦 + 未选中      → 无边
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AppleTvColors.GlassBorderFocused
            selected -> AppleTvColors.AppleBlue
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180),
        label = "chipFocusBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            focused -> 2.5.dp
            selected -> 1.5.dp
            else -> 0.dp
        },
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
                    focused = isFocused
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
    // chip 按 UP 时的显式焦点目标(通常是放大镜搜索按钮),保证分类↔搜索
    // 可回溯,不依赖空间位置猜测。
    upTarget: FocusRequester? = null,
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
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .then(
                        if (upTarget != null) {
                            Modifier.focusProperties { up = upTarget }
                        } else {
                            Modifier
                        },
                    ),
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
    var endReached by remember(items.size, canLoadMore) { mutableStateOf(false) }
    val shouldLoadMore by remember(items.size, canLoadMore, loadingMore, state) {
        derivedStateOf {
            val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            canLoadMore &&
                !loadingMore &&
                items.isNotEmpty() &&
                lastVisible >= items.lastIndex - 6
        }
    }
    val endVisible by remember(items.size, canLoadMore, loadingMore, state) {
        derivedStateOf {
            val lastVisible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            !canLoadMore &&
                !loadingMore &&
                items.isNotEmpty() &&
                lastVisible >= items.lastIndex
        }
    }
    val showEndOfList = !canLoadMore && !loadingMore && items.isNotEmpty() && endReached

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }
    LaunchedEffect(endVisible) {
        if (endVisible) {
            endReached = true
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            contentPadding = PaddingValues(
                start = 56.dp,
                end = 56.dp,
                top = 16.dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
            // 仍可加载时保留滚动尾部提示;加载中/已到底改用下方状态栏,
            // 避免文案被网格底部裁切。
            if (!loadingMore && canLoadMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadMoreIndicator(loadingMore = false)
                }
            }
        }

        if (loadingMore || showEndOfList) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (loadingMore) {
                    LoadMoreIndicator(loadingMore = true)
                } else {
                    EndOfListIndicator()
                }
            }
        }
    }
}

@Composable
private fun LoadMoreIndicator(
    loadingMore: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadingMore) {
            CircularProgressIndicator(
                color = AppleTvColors.AppleBlue,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "正在加载更多…",
                color = AppleTvColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            // 还能加载:展示“下滑加载更多”静态指示器(向下箭头 + 文案)
            ChevronDownGlyph(size = 16.dp, color = AppleTvColors.TextTertiary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "下滑加载更多",
                color = AppleTvColors.TextTertiary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EndOfListIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(1.dp)
                .background(AppleTvColors.GlassBorder),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "已经到底啦",
            color = AppleTvColors.TextTertiary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(1.dp)
                .background(AppleTvColors.GlassBorder),
        )
    }
}

@Composable
private fun ChevronDownGlyph(size: androidx.compose.ui.unit.Dp, color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.35f),
            end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.35f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
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
    // 焦点态使用强调色:Apple Blue 实心 + 白字(焦点 = 当前操作位置,用最醒目的配色);
    // 失焦态:白底 + 深色字(高对比,文字始终清晰可读)。
    val bg = if (focused) AppleTvColors.AppleBlue else Color.White
    val textColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.DeepSpace

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(AppleTvShapes.Button)
            .background(bg)
            .tvFocusableLite(
                shape = AppleTvShapes.Button,
                focusedScale = 1.06f,
                interactionSource = interactionSource,
                onClick = onClick,
                onFocusChange = { focused = it },
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
 * 完全自管理焦点态:焦点高亮只由按钮自身通过 [tvFocusableLite] 的 onFocusChange
 * (内部 onFocusChanged 直接读节点真实焦点态)驱动。失焦时一定会回落,**不需要**
 * 父级用 focusedOverride 之类的外部状态去强制清除——那是不可靠机制下的妥协方案,已移除。
 */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // 跳过节点**第一次**自动 focus 触发的回调。
    // 原因:Compose 在冷启动 / Composable 首次进入屏幕时,会主动把焦点给到
    // 第一个 focusable 元素,这一发 onFocusChange(isFocused=true) 是**框架行为**,
    // 不是用户操作;如果不区分,会导致 `onFocused = { searchVisible = true }`
    // 这类副作用在冷启动时自动执行(用户反馈:选择搜索以后,顶栏就自动变成搜索模式)。
    // 真正的"用户导航到该按钮"是**第二次及以后**的 focus 事件。
    val skipFirstFocus = remember { mutableStateOf(true) }
    val bg = if (focused) AppleTvColors.AppleBlue else AppleTvColors.GlassFill
    val textColor = if (focused) AppleTvColors.TextPrimary else AppleTvColors.TextSecondary
    val borderColor = if (focused) Color.Transparent else AppleTvColors.GlassBorder

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(AppleTvShapes.Button)
            .background(bg)
            .border(1.dp, borderColor, AppleTvShapes.Button)
            .tvFocusableLite(
                shape = AppleTvShapes.Button,
                focusedScale = 1.06f,
                interactionSource = interactionSource,
                onClick = onClick,
                onFocusChange = { isFocused ->
                    focused = isFocused
                    if (isFocused) {
                        if (skipFirstFocus.value) {
                            skipFirstFocus.value = false
                        } else {
                            onFocused()
                        }
                    }
                },
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
