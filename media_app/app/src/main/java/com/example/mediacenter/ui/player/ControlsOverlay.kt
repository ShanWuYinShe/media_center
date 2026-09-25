package com.example.mediacenter.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.UiIcons
import com.example.mediacenter.util.formatDuration

@Composable
internal fun ControlsOverlay(
    videoTitle: String,
    playbackResult: PlaybackResult,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    controlsFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQuality: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentQn = playbackResult.options.firstOrNull()?.qn
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.55f))) {
        // 顶部:标题 + 返回
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = videoTitle,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // TvIconButton:遥控器聚焦时有 Apple Blue 高亮背景，纯图标在 10ft 下焦点不可见
            TvIconButton(onClick = onBack, contentDescription = "返回") {
                Icon(UiIcons.ArrowBack, contentDescription = null, tint = TextPrimary)
            }
        }
        // 底部:进度 + 播放控制 + 分P + 清晰度
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 进度条与时间同行(Row 内横向 weight)。此前 Slider 在 Column 里用
            // vertical weight,会把整个底栏撑满全屏高度、遮住整幅画面——已修复。
            var dragPositionMs by remember { mutableStateOf<Float?>(null) }
            val sliderMax = durationMs.toFloat().coerceAtLeast(1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 焦点在 Slider 上时,左右键 = 快进/快退 10s(长按连续触发)
                Slider(
                    value = (dragPositionMs ?: positionMs.toFloat()).coerceIn(0f, sliderMax),
                    onValueChange = { value ->
                        dragPositionMs = value
                    },
                    onValueChangeFinished = {
                        dragPositionMs?.let { onSeek(it.toLong()) }
                        dragPositionMs = null
                    },
                    valueRange = 0f..sliderMax,
                    modifier = Modifier
                        .weight(1f)
                        .requiredHeight(48.dp)
                        // 失焦即复位预览值:否则进度条残留拖动预览、再聚焦从旧值续算 seek
                        .onFocusChanged { if (!it.isFocused) dragPositionMs = null }
                        .onPreviewKeyEvent { event ->
                            // 与拖动同款两段式：KeyDown 只更新本地预览值，KeyUp 才提交
                            // 真实 seek。否则长按方向键的连发会产生一串连续 seek 导致卡顿。
                            val leftRight =
                                event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                            when {
                                event.type == KeyEventType.KeyDown && leftRight -> {
                                    val delta =
                                        if (event.key == Key.DirectionLeft) -SEEK_STEP_MS else SEEK_STEP_MS
                                    val base = dragPositionMs ?: positionMs.toFloat()
                                    dragPositionMs = base.plus(delta).coerceIn(0f, sliderMax)
                                    true
                                }
                                event.type == KeyEventType.KeyUp && dragPositionMs != null -> {
                                    val pending = dragPositionMs
                                    if (pending != null) onSeek(pending.toLong())
                                    dragPositionMs = null
                                    true
                                }
                                else -> false
                            }
                        },
                    colors = SliderDefaults.colors(
                        thumbColor = AppleBlue,
                        activeTrackColor = AppleBlue,
                        inactiveTrackColor = DividerGray,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    color = TextSecondary,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvIconButton(
                    onClick = onTogglePlay,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.focusRequester(controlsFocus),
                ) {
                    Icon(
                        imageVector = if (isPlaying) UiIcons.Pause else UiIcons.PlayArrow,
                        contentDescription = null,
                        tint = TextPrimary,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            // 分P 行:独立 LazyRow,B 站几十甚至上百 P 的视频也能横向滚动选择,
            // 不会把播放按钮挤出屏宽。focusGroup 让 DPad 从上方 Slider/按钮落入更顺滑。
            if (playbackResult.pages.totalPages > 1) {
                Spacer(Modifier.height(8.dp))
                // 控制条出现时把当前 P 滚进可视区(+1 是行首"分P"标签项),
                // 否则上百 P 的视频当前选中 chip 在屏幕外,用户不知道现在播到哪一集。
                val pagesListState = rememberLazyListState()
                LaunchedEffect(playbackResult.pages.currentPage) {
                    pagesListState.animateScrollToItem(playbackResult.pages.currentPage + 1)
                }
                LazyRow(
                    state = pagesListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup(),
                ) {
                    item(key = "label") {
                        Text("分P", color = TextSecondary, fontSize = 14.sp)
                    }
                    itemsIndexed(
                        items = playbackResult.pages.pageTitles,
                        key = { index, _ -> index },
                    ) { index, pageTitle ->
                        SelectChip(
                            label = if (pageTitle.isBlank()) "${index + 1}P" else "${index + 1}P ${pageTitle}",
                            selected = index == playbackResult.pages.currentPage,
                            onClick = { onSelectPage(index) },
                        )
                    }
                }
            }
            if (playbackResult.availableQualities.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // focusGroup:清晰度数量有限(<10)保留 Row,但聚合为一组让 DPad 焦点落入更顺滑。
                    modifier = Modifier.focusGroup(),
                ) {
                    Text("清晰度", color = TextSecondary, fontSize = 14.sp)
                    playbackResult.availableQualities.forEach { quality ->
                        SelectChip(
                            label = quality.label,
                            selected = quality.qn == currentQn,
                            onClick = { onSelectQuality(quality.qn) },
                        )
                    }
                }
            }
        }
    }
}
