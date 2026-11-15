package com.example.mediacenter.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.CardShape
import com.example.mediacenter.ui.theme.CardSpacing
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.VideoGridMinWidth

/**
 * 目录/搜索首屏加载骨架:与 [PagedVideoGrid] 同列宽、同卡片比例的占位卡,
 * 替代居中转圈——10ft 观看距离下用户能立刻看到"内容结构正在加载",且真实
 * 内容到达时不会布局跳动。
 *
 * 呼吸动画挂在整层 graphicsLayer 的 draw lambda 上(只重绘、不重组),
 * 低端 TV 盒子也不会因为骨架屏本身掉帧;不做逐卡渐变动画。
 */
@Composable
fun VideoGridSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 9,
) {
    val transition = rememberInfiniteTransition(label = "video-grid-skeleton")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "video-grid-skeleton-breath",
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(VideoGridMinWidth),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            bottom = 32.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(CardSpacing),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
        userScrollEnabled = false,
        modifier = modifier.fillMaxWidth().graphicsLayer { alpha = breath },
    ) {
        items(itemCount) { SkeletonCard() }
    }
}

@Composable
private fun SkeletonCard() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(CardShape)
                .background(CardGray),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DividerGray.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DividerGray.copy(alpha = 0.4f)),
        )
    }
}
