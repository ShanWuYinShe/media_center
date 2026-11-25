package com.example.mediacenter.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.components.episodeDisplayTitle
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.ScreenVerticalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.util.formatDuration

private val OverlayTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.85f),
    offset = Offset(2f, 2f),
    blurRadius = 8f,
)

/**
 * 播放页信息层：只显示状态、进度和按键说明，不提供可聚焦的播放/快进/切集按钮。
 *
 * 老人遥控器操作的核心是"按键直接生效"：方向键和 OK 在根节点统一处理，
 * 控制层出现时不改变焦点，不需要先瞄准某个按钮再按 OK。唯一的交互入口是
 * 触摸用的返回/选集胶囊；电视端返回用 BACK，选集用 MENU（或触摸点胶囊）。
 */
@Composable
internal fun ControlsOverlay(
    videoTitle: String,
    playbackResult: PlaybackResult,
    collection: PlaybackCollection?,
    positionMs: Long,
    durationMs: Long,
    // 是否真的有上一集/下一集:没有时不要显示对应按键提示,否则按了没反应
    // 用户会以为遥控器/App 坏了(多P第 1 页、合集第 1/最后一集都很常见)。
    hasPrevious: Boolean,
    hasNext: Boolean,
    onOptionsClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = playbackResult.pages
    val hasEpisodes = pages.totalPages > 1
    val episodeLabel = playbackEpisodeLabel(playbackResult, collection)
    val qualityLabel = playbackResult.options.firstOrNull()?.label
        ?.takeIf { it.isNotBlank() }
        ?: "默认"
    val hasOptions = hasEpisodes || playbackResult.availableQualities.size > 1
    val optionsLabel = if (hasEpisodes) "选集 · $qualityLabel" else "清晰度 · $qualityLabel"

    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.18f))) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        1f to Color.Transparent,
                    ),
                )
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(top = ScreenVerticalPadding, bottom = 20.dp)
                // 吃掉信息层空白处的触摸，避免点进度条/标题时穿透到全屏
                // 点按层触发播放/暂停。
                .pointerInput(Unit) { detectTapGestures { } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 触摸/鼠标可点；电视端不参与焦点遍历，避免把焦点从播放根节点抢走。
            TvChip(
                label = "返回",
                onClick = onBack,
                leading = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.width(20.dp).height(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                },
                modifier = Modifier.focusProperties { canFocus = false },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = videoTitle,
                    color = TextPrimary,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
                )
                if (episodeLabel != null) {
                    Text(
                        text = episodeLabel,
                        color = TextSecondary,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
                    )
                }
            }
            if (hasOptions) {
                TvChip(
                    label = optionsLabel,
                    onClick = onOptionsClick,
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                )
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(top = 24.dp, bottom = 32.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            PlaybackProgress(positionMs = positionMs, durationMs = durationMs)
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyHint("←", "快退 10 秒")
                Spacer(Modifier.width(20.dp))
                KeyHint("→", "快进 10 秒")
                if (hasPrevious) {
                    Spacer(Modifier.width(20.dp))
                    KeyHint("↑", "上一集")
                }
                if (hasNext) {
                    Spacer(Modifier.width(20.dp))
                    KeyHint("↓", "下一集")
                }
                Spacer(Modifier.width(20.dp))
                KeyHint("OK", "播放/暂停")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("长按 ← → 连续快退/快进")
                    // 遥控器上 MENU 键与长按 OK 都能打开选集/清晰度弹窗,
                    // 提示里两个入口都写出来,避免用户不知道 MENU 可用。
                    if (hasOptions) {
                        val optionsHint = when {
                            hasEpisodes && playbackResult.availableQualities.size > 1 -> "选集/清晰度"
                            hasEpisodes -> "选集"
                            else -> "清晰度"
                        }
                        append(" · MENU/长按 OK $optionsHint")
                    }
                },
                color = TextSecondary,
                fontSize = 13.sp,
                style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun PlaybackProgress(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 读屏(TalkBack)下进度条本身没有可读文本;整行合并成一个语义节点,
        // 播报「播放进度 12:34 / 45:00」而不是四个独立数字。
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription =
                "播放进度 ${formatDuration(positionMs)} / ${formatDuration(durationMs)}"
        },
    ) {
        Text(
            text = formatDuration(positionMs),
            color = TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
        )
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(DividerGray, RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(
                        com.example.mediacenter.ui.theme.AppleBlue,
                        RoundedCornerShape(999.dp),
                    ),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = formatDuration(durationMs),
            color = TextSecondary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
        )
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .widthIn(min = 30.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = key,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = LocalTextStyle.current.copy(shadow = OverlayTextShadow),
        )
    }
}

/** 选集/清晰度弹窗：由 MENU 键或触摸右上角胶囊打开。 */
@Composable
internal fun PlaybackOptionsDialog(
    playbackResult: PlaybackResult,
    onSelectQuality: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pages = playbackResult.pages
    val qualities = playbackResult.availableQualities
    val currentQn = playbackResult.options.firstOrNull()?.qn
    val firstFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val closeButtonFocus = remember { FocusRequester() }
    val pagesState = rememberLazyListState()
    val hasEpisodes = pages.totalPages > 1
    val hasQualities = qualities.size > 1
    LaunchedEffect(Unit) {
        if (hasEpisodes) {
            runCatching { pagesState.scrollToItem(pages.currentPage) }
        }
        val target = when {
            hasEpisodes -> firstFocus
            hasQualities -> qualityFocus
            else -> closeButtonFocus
        }
        runCatching { target.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (hasEpisodes) "选集与清晰度" else "清晰度", color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = LayoutArrangement.spacedBy(14.dp)) {
                if (hasEpisodes) {
                    Text(
                        text = "选集",
                        color = TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyRow(
                        state = pagesState,
                        horizontalArrangement = LayoutArrangement.spacedBy(8.dp),
                        // 选集行向下明确指向清晰度行(或无清晰度时直通关闭按钮):
                        // 当集数较多滚到靠右(如第 20 集)时,下方若无垂直对齐的清晰度项,
                        // 2D 空间导航会搜空导致焦点卡死在选集行出不来。
                        modifier = Modifier.focusProperties {
                            down = if (hasQualities) qualityFocus else closeButtonFocus
                        },
                    ) {
                        itemsIndexed(
                            items = pages.pageTitles,
                            key = { index, _ -> index },
                        ) { index, pageTitle ->
                            TvChip(
                                label = episodeDisplayTitle(index, pageTitle),
                                selected = index == pages.currentPage,
                                onClick = {
                                    onSelectPage(index)
                                    onDismiss()
                                },
                                modifier = Modifier.then(
                                    if (index == pages.currentPage) {
                                        Modifier.focusRequester(firstFocus)
                                    } else {
                                        Modifier
                                    },
                                ),
                            )
                        }
                    }
                }
                if (hasQualities) {
                    Text(
                        text = "清晰度",
                        color = TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyRow(
                        horizontalArrangement = LayoutArrangement.spacedBy(8.dp),
                        // 清晰度行向上返回当前选集,向下直达关闭按钮
                        modifier = Modifier.focusProperties {
                            if (hasEpisodes) up = firstFocus
                            down = closeButtonFocus
                        },
                    ) {
                        itemsIndexed(
                            items = qualities,
                            key = { _, quality -> quality.qn },
                        ) { index, quality ->
                            val isCurrentQuality = quality.qn == currentQn || (currentQn == null && index == 0)
                            TvChip(
                                label = quality.label,
                                selected = quality.qn == currentQn,
                                onClick = {
                                    onSelectQuality(quality.qn)
                                    onDismiss()
                                },
                                modifier = Modifier.then(
                                    if (isCurrentQuality) {
                                        Modifier.focusRequester(qualityFocus)
                                    } else {
                                        Modifier
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TvDialogButton(
                label = "关闭",
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(closeButtonFocus)
                    .focusProperties {
                        up = if (hasQualities) qualityFocus else if (hasEpisodes) firstFocus else FocusRequester.Default
                    },
            )
        },
        containerColor = CardGray,
    )
}
