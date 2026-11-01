package com.example.mediacenter.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape
import coil.compose.AsyncImage
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.util.formatSeconds

/**
 * 首页 Hero 数据:优先展示"继续观看"的最新一条,没有观看记录时回退到
 * 首个分类的第一条内容。Hero 只负责展示与两个明确动作(播放/详情),
 * 不承载额外状态。
 */
data class FeaturedVideo(
    val video: VideoSummary,
    val page: Int = 0,
    // 0f..1f;非空时 Hero 底部显示进度条
    val progress: Float? = null,
    val badge: String,
    val meta: String? = null,
    val description: String? = null,
)

/**
 * 首页大图 Hero:16:9 封面 + 由左/下向背景渐隐的双层遮罩,标题与操作
 * 贴底对齐。相比纯卡片行,首屏有明确的视觉重心,10ft 下也能立刻判断
 * "这是什么内容、要不要继续看"。
 */
@Composable
fun FeaturedHero(
    item: FeaturedVideo,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .focusRestorer()
            .fillMaxWidth()
            // 1080p TV 常见为 960×540dp:360dp 约占 2/3 屏高,保留下一行
            // 露出边缘,提示"下面还有内容可滚动"。
            .height(360.dp)
            .padding(horizontal = ScreenHorizontalPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CardGray, RoundedCornerShape(18.dp)),
        ) {
            AsyncImage(
                model = item.video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(CardGray),
                error = ColorPainter(CardGray),
            )
            // 左侧压暗保证文字对比度;底部融进页面背景,避免大图"贴"在
            // 黑色页面上形成生硬边。两层遮罩叠加而非单色半透黑。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.78f),
                            0.55f to Color.Black.copy(alpha = 0.25f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to BackgroundDark.copy(alpha = 0.92f),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxHeight()
                    .padding(horizontal = 36.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = item.badge,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(AppleBlue, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = item.video.title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
                item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = meta,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = desc,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 780.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        shape = tvPillButtonShape(),
                        border = tvPillButtonBorder(),
                        scale = tvButtonFocusScale(),
                        onClick = onPlay,
                        modifier = Modifier.requiredHeight(52.dp),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleBlue,
                            focusedContainerColor = AppleBlue,
                            contentColor = TextPrimary,
                            focusedContentColor = TextPrimary,
                        ),
                    ) {
                        Text(
                            text = if (item.progress != null) "继续播放" else "播放",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                    Button(
                        shape = tvPillButtonShape(),
                        border = tvPillButtonBorder(),
                        scale = tvButtonFocusScale(),
                        onClick = onDetails,
                        modifier = Modifier.requiredHeight(52.dp),
                        colors = ButtonDefaults.colors(
                            containerColor = CardGray.copy(alpha = 0.85f),
                            focusedContainerColor = AppleBlue,
                            contentColor = TextPrimary,
                            focusedContentColor = TextPrimary,
                        ),
                    ) {
                        Text(
                            text = "详情",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                // 继续观看进度:贴 Hero 底边,与卡片进度条同一视觉语言
                item.progress?.let { progress ->
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(AppleBlue, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** 继续观看条目的 Hero 文案(纯函数,便于单测)。 */
internal fun continueMeta(positionSeconds: Long, durationSeconds: Long): String {
    if (durationSeconds <= 0) return "继续观看"
    val remaining = (durationSeconds - positionSeconds).coerceAtLeast(0)
    val percent = (positionSeconds.toFloat() / durationSeconds * 100).toInt().coerceIn(0, 100)
    return "继续观看 · 已看 $percent% · 剩余 ${formatSeconds(remaining)}"
}
