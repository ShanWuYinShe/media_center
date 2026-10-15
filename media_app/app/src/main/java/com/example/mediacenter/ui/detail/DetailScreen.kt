package com.example.mediacenter.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.mediacenter.ui.theme.CardShape
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
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
    onPlay: (page: Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // Apple TV 式左右分栏:左封面垂直居中,右侧信息+操作区独立滚动。
    // 单列长滚动会把播放按钮推到首屏之外,10ft 场景下进入详情页应一眼
    // 看到封面与"播放",一屏完成"看什么/看不看"的决策。
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            // 左列封面占 45% 宽、16:9、圆角;源图为横版封面,裁成竖版海报
            // 会切掉两侧主体,故保持横版并垂直居中。
            modifier = Modifier.weight(0.45f).aspectRatio(16f / 9f).clip(CardShape),
            contentScale = ContentScale.Crop,
            // 图片加载中/失败时以玻璃灰占位
            placeholder = ColorPainter(CardGray),
            error = ColorPainter(CardGray),
        )
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
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
            // 继续观看:上次观看的分页与绝对位置(多P),点击播放时直达该页该处
            var lastWatchedPage by remember(video.id) { mutableStateOf(0) }
            var lastWatchedSeconds by remember(video.id) { mutableLongStateOf(0L) }
            LaunchedEffect(video.id) {
                resumePosition = withContext(Dispatchers.IO) {
                    PlaybackPositionStore.resumePosition(context, video.id, (video.durationSeconds ?: 0L) * 1000L)
                }
                lastWatchedPage = withContext(Dispatchers.IO) { WatchHistory.pageOf(context, video.id) }
                lastWatchedSeconds = withContext(Dispatchers.IO) {
                    WatchHistory.entries(context).firstOrNull { it.videoId == video.id }?.positionSeconds ?: 0L
                }
            }
            val hintText = when {
                lastWatchedPage > 0 && lastWatchedSeconds >= 30 ->
                    "上次看到第 ${lastWatchedPage + 1} 页 ${formatDuration(lastWatchedSeconds)}，播放将从此处继续"
                resumePosition > 0 ->
                    "上次播放到 ${formatDuration(resumePosition)}，播放将从此处继续"
                else -> null
            }
            if (hintText != null) {
                Text(
                    text = hintText,
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
                    // 登记观看历史(继续观看行):元数据齐全,保留上次观看页;位置由
                    // 播放器退出时回写。provider 取点击时刻的当前源,首页继续观看行
                    // 按当前源过滤展示。
                    WatchHistory.upsert(
                        context,
                        WatchEntry(
                            videoId = video.id,
                            provider = MediaRepositoryHolder.repository.currentProvider,
                            title = video.title,
                            thumbnailUrl = video.thumbnailUrl.orEmpty(),
                            durationSeconds = video.durationSeconds ?: 0L,
                            page = lastWatchedPage,
                        ),
                    )
                    onPlay(lastWatchedPage)
                },
                modifier = Modifier.requiredHeight(56.dp).fillMaxWidth().focusRequester(playFocus),
                colors = ButtonDefaults.colors(containerColor = AppleBlue),
            ) {
                Text("播放", color = TextPrimary, fontSize = 18.sp)
            }
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
