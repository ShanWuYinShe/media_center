package com.example.mediacenter.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import coil.compose.AsyncImage
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.util.formatSeconds

@Composable
fun VideoCard(
    video: VideoSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    // 不用 requiredWidth/requiredHeight 钉死尺寸——会让卡片在 CategoryVideosScreen
    // 的 GridCells.Adaptive(280.dp) 里无法 fillMaxWidth，4K/平板上也会偏小。
    // 改用 aspectRatio 让卡片宽高比固定(16:10)，宽度交给父网格分配。
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(16f / 10f),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(containerColor = CardGray),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // 缩略图加载中/失败时以玻璃灰占位,避免空白卡片
                placeholder = ColorPainter(CardGray),
                error = ColorPainter(CardGray),
            )
            // 底部渐变遮罩:B 站缩略图常有亮色区域,10ft 距离下白色标题直接叠
            // 在图上可读性差;与时长徽章(黑底)一样保证对比度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 两行标题(2×~20sp)+内边距需要 ~72dp 渐变遮罩，52dp 会盖不住第二行
                    .height(72.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        // 三段式非线性渐变:封面底部为亮色时,单向渐变的过渡带
                        // 仍会吞掉第二行标题;中段提前压暗、底端加深,对比更稳。
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )
            Text(
                text = video.title,
                color = TextPrimary,
                style = LocalTextStyle.current.copy(
                    // 10ft 距离下微弱投影:标题叠在亮色封面上仍保持可读
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f,
                    ),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            val duration = video.durationSeconds
            if (duration != null && duration > 0) {
                Text(
                    text = formatSeconds(duration),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 观看进度条(继续观看行):贴卡片底边,焦点放大时随卡片同步缩放
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(AppleBlue),
                    )
                }
            }
        }
    }
}
