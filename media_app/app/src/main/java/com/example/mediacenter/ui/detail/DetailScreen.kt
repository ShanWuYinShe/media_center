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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import coil.compose.AsyncImage
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.TvScrollableText
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.formatDuration
import com.example.mediacenter.util.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // 与播放页 READY 时的恢复判定一致,避免提示了却不恢复)。SharedPreferences
        // 首次访问会同步加载 XML,放 IO 调度器异步读,避免主线程进详情页卡顿。
        val context = LocalContext.current
        var resumePosition by remember(video.id) { mutableLongStateOf(0L) }
        LaunchedEffect(video.id) {
            resumePosition = withContext(Dispatchers.IO) {
                PlaybackPositionStore.resumePosition(context, video.id, (video.durationSeconds ?: 0L) * 1000L)
            }
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
        // 只按真实文本布局判断是否溢出,固定折叠 3 行,不在原布局内展开。
        // 必须用 hasVisualOverflow:maxLines=3 时 lineCount 恒 <=3,"行数>3"永远为 false,
        // 披露按钮会永不出现。remember 带 video.id:换视频时重置溢出状态。
        var descOverflowed by remember(video.id) { mutableStateOf(false) }
        Text(
            desc.ifEmpty { "暂无简介" },
            color = TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult -> descOverflowed = layoutResult.hasVisualOverflow },
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
        // 主操作(播放)获得进入时焦点：简介溢出时上方还有"查看完整简介"按钮，
        // 不指定焦点路径遥控器会先落在那里，多按一次才能到播放。
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(video.id) { playFocus.requestFocus() }
        Button(
            onClick = {
                // 登记观看历史(继续观看行):元数据齐全,位置由播放器退出时回写。
                // provider 取点击时刻的当前源,首页继续观看行按当前源过滤展示。
                WatchHistory.upsert(
                    context,
                    WatchEntry(
                        videoId = video.id,
                        provider = MediaRepositoryHolder.repository.currentProvider,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl.orEmpty(),
                        durationSeconds = video.durationSeconds ?: 0L,
                    ),
                )
                onPlay()
            },
            modifier = Modifier.requiredHeight(56.dp).fillMaxWidth().focusRequester(playFocus),
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
            // 遥控器可滚动的长文：普通 verticalScroll 对 D-pad 无效
            TvScrollableText(
                text = description,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                maxHeight = 360.dp,
            )
        },
        confirmButton = {
            // 主按钮获得进入焦点(项目约定)，读简介时再按上键进入正文滚动
            val closeFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { closeFocus.requestFocus() }
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) {
                Text("关闭", color = TextPrimary)
            }
        },
        containerColor = CardGray,
    )
}
