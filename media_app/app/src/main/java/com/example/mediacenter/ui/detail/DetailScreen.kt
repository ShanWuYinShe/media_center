package com.example.mediacenter.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.compose.AsyncImage
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.formatDuration
import com.example.mediacenter.util.formatSeconds

@Composable
fun DetailScreen(
    video: VideoSummary,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            // 用 aspectRatio 而非 requiredHeight(300.dp)：在窄屏/横屏/4K 上自适应，
            // 同时给 TalkBack 一个可读的图片标签(视频标题)。
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            contentScale = ContentScale.Crop,
            // 图片加载中/失败时以玻璃灰占位
            placeholder = ColorPainter(CardGray),
            error = ColorPainter(CardGray),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            video.title,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val duration = video.durationSeconds
        if (duration != null && duration > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatSeconds(duration),
                color = TextTertiary,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 断点续播提示:上次位置有效时告知将从该处继续(传时长过滤"已看完"记录,
        // 与播放页 READY 时的恢复判定一致,避免提示了却不恢复)
        val context = LocalContext.current
        val resumePosition = remember(video.id) {
            PlaybackPositionStore.resumePosition(context, video.id, (video.durationSeconds ?: 0L) * 1000L)
        }
        if (resumePosition > 0) {
            Text(
                text = "上次播放到 ${formatDuration(resumePosition)}，播放将从此处继续",
                color = TextTertiary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        val desc = video.description.orEmpty()
        // 只按真实文本布局判断是否溢出,固定折叠 3 行,不在原布局内展开
        var descOverflowed by remember { mutableStateOf(false) }
        Text(
            desc.ifEmpty { "暂无简介" },
            color = TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult -> descOverflowed = layoutResult.lineCount > 3 },
        )
        if (descOverflowed) {
            var showFullDesc by remember { mutableStateOf(false) }
            TextButton(onClick = { showFullDesc = true }) {
                Text("查看完整简介", color = AppleBlue)
            }
            if (showFullDesc) {
                FullDescriptionDialog(
                    title = video.title,
                    description = desc,
                    onDismiss = { showFullDesc = false },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPlay,
            modifier = Modifier.requiredHeight(56.dp).fillMaxWidth(),
            colors = ButtonDefaults.colors(containerColor = AppleBlue),
        ) {
            Text("播放", color = TextPrimary, fontSize = 18.sp)
        }
    }
}

// 完整简介在独立 Dialog 阅读卡片中展示,保留 BACK 关闭路径,不在详情页原布局内撑开。
@Composable
private fun FullDescriptionDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Text(
                description,
                color = TextSecondary,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TextPrimary)
            }
        },
        containerColor = CardGray,
    )
}
