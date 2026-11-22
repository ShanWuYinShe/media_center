package com.example.mediacenter.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackPrefetcher
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.PlaybackTuning
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.playbackStorageKey
import com.example.mediacenter.data.providerDisplayName
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.TvActionButton
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.components.TvBackButton
import com.example.mediacenter.ui.components.TvScrollableText
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.CardShape
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.util.BackgroundWork
import com.example.mediacenter.util.formatDuration
import com.example.mediacenter.util.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(
    video: VideoSummary,
    onPlay: (page: Int, qualityQn: Int?) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // 停留 1.2s 才预取播放地址:快速浏览列表不触发,避免把 YouTube 冷解析
    // (占用 extractor 并发)浪费在"只看一眼"的视频上;玩家点"播放"时通常已就绪。
    // 按播放页同一口径的自动选档上限预取首段媒体,播放页才会命中缓存。
    // 同一份解析结果也用于详情页的清晰度/多P选集展示(见下方 detailPlaybackUi),
    // 不额外发请求:PlaybackPrefetcher.resolve 等待/复用这次预取。
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val prefetchMaxQn = remember(configuration.screenHeightDp, density.density) {
        PlaybackTuning.maxInitialQualityQn(
            (configuration.screenHeightDp * density.density).roundToInt(),
        )
    }
    val repository = MediaRepositoryHolder.repository
    var playbackInfo by remember(video.id) { mutableStateOf<PlaybackResult?>(null) }
    LaunchedEffect(video.id) {
        delay(1200)
        PlaybackPrefetcher.prefetch(
            repository,
            video.id,
            maxInitialQualityQn = prefetchMaxQn,
        )
        playbackInfo = PlaybackPrefetcher.resolve(repository, video.id, qn = null)
    }
    val provider = repository.currentProvider

    // 全屏封面做背景(压暗 + 渐隐到页面底色),前景是"封面 + 信息/操作"分栏。
    // 比纯黑底更有内容氛围,也不会像单列长滚动那样把播放按钮推到首屏外。
    Box(modifier = Modifier.fillMaxSize()) {
        // 详情页用服务端下发的大图(卡片缩略图只有 ~640px,铺满全屏会糊)。
        val posterUrl = video.thumbnailUrlLarge ?: video.thumbnailUrl
        // 背景只做压暗氛围层:优先用卡片尺寸图(列表里通常已在 Coil 缓存中,
        // 不会为背景再拉一次大图),并显式限制解码尺寸(640×360),避免与前景
        // 大图各自按全屏尺寸再解码一份(~8MB 位图)。
        val context = LocalContext.current
        val backdropUrl = video.thumbnailUrl?.takeIf { it.isNotBlank() } ?: posterUrl
        val backdropModel = remember(backdropUrl, context) {
            ImageRequest.Builder(context)
                .data(backdropUrl)
                .size(640, 360)
                .build()
        }
        AsyncImage(
            model = backdropModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(0.32f),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(BackgroundDark),
            error = ColorPainter(BackgroundDark),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to BackgroundDark.copy(alpha = 0.70f),
                        0.45f to BackgroundDark.copy(alpha = 0.90f),
                        1f to BackgroundDark,
                    ),
                ),
        )

        val backFocus = remember { FocusRequester() }
        val playFocus = remember { FocusRequester() }
        val descFocus = remember { FocusRequester() }
        val firstQualityFocus = remember { FocusRequester() }
        val firstEpisodeFocus = remember { FocusRequester() }
        var descOverflowed by remember(video.id) { mutableStateOf(false) }
        // 详情页上的手动选择:清晰度(qn)与分P下标。null = 跟随自动档/续播页。
        var selectedQualityQn by remember(video.id) { mutableStateOf<Int?>(null) }
        var selectedPageOverride by remember(video.id) { mutableStateOf<Int?>(null) }
        var showEpisodeDialog by remember(video.id) { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenHorizontalPadding, vertical = 24.dp),
        ) {
            // 顶栏:返回 + 来源标识。TV 上给 BACK 键一个可见的对应控件,
            // 不依赖用户记住遥控器按键;与下方主操作建立 D-pad 连通拓扑。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .focusRequester(backFocus)
                        .focusProperties {
                            down = if (descOverflowed) descFocus else playFocus
                        },
                )
                Text(
                    text = providerDisplayName(provider),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(CardGray.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    // 左列封面占 45% 宽、16:9、圆角;源图为横版封面,裁成竖版海报
                    // 会切掉两侧主体,故保持横版并与右侧标题同顶线。
                    modifier = Modifier.weight(0.45f).aspectRatio(16f / 9f).clip(CardShape),
                    contentScale = ContentScale.Crop,
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
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val duration = video.durationSeconds
                    val year = video.year?.takeIf { it.isNotBlank() }
                    if ((duration != null && duration > 0) || year != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (duration != null && duration > 0) {
                                Text(
                                    text = formatSeconds(duration),
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .background(CardGray.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            if (year != null) {
                                Text(
                                    text = year,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .background(CardGray.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 断点续播提示:上次位置有效时告知将从该处继续(传时长过滤"已看完"记录,
                    // 与播放页 READY 时的恢复判定一致,避免提示了却不恢复)。SharedPreferences
                    // 首次访问会同步加载 XML,放 IO 调度器异步读,避免主线程进详情页卡顿。
                    var resumePosition by remember(video.id) { mutableLongStateOf(0L) }
                    // 继续观看:上次观看的分页与绝对位置(多P),点击播放时直达该页该处
                    var lastWatchedPage by remember(video.id) { mutableIntStateOf(0) }
                    var lastWatchedSeconds by remember(video.id) { mutableLongStateOf(0L) }
                    LaunchedEffect(video.id) {
                        lastWatchedPage = withContext(Dispatchers.IO) { WatchHistory.pageOf(context, video.id) }
                        lastWatchedSeconds = withContext(Dispatchers.IO) {
                            WatchHistory.entries(context).firstOrNull { it.videoId == video.id }?.positionSeconds ?: 0L
                        }
                        val resumePage = resumePageIndex(video.id, provider, lastWatchedPage)
                        resumePosition = withContext(Dispatchers.IO) {
                            PlaybackPositionStore.resumePosition(
                                context,
                                playbackStorageKey(video.id, resumePage),
                                (video.durationSeconds ?: 0L) * 1000L,
                            )
                        }
                    }
                    val resumePage = resumePageIndex(video.id, provider, lastWatchedPage)
                    val hintText = when {
                        lastWatchedPage > 0 && lastWatchedSeconds >= 30 ->
                            "上次看到第 ${lastWatchedPage + 1} 页 ${formatDuration(lastWatchedSeconds)}，播放将从此处继续"
                        resumePosition > 0 && resumePage > 0 ->
                            "上次看到第 ${resumePage + 1} 页 ${formatDuration(resumePosition)}，播放将从此处继续"
                        resumePosition > 0 ->
                            "上次播放到 ${formatDuration(resumePosition)}，播放将从此处继续"
                        else -> null
                    }
                    if (hintText != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = hintText,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .background(AppleBlueDark.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val desc = video.description.orEmpty()
                    // 只按真实文本布局判断是否溢出,固定折叠 3 行,不在原布局内展开。
                    // 必须用 hasVisualOverflow:maxLines=3 时 lineCount 恒 <=3,"行数>3"永远为 false,
                    // 披露按钮会永不出现。
                    Text(
                        desc.ifEmpty { "暂无简介" },
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layoutResult -> descOverflowed = layoutResult.hasVisualOverflow },
                    )
                    if (descOverflowed) {
                        var showFullDesc by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(8.dp))
                        TvChip(
                            label = "查看完整简介",
                            onClick = { showFullDesc = true },
                            modifier = Modifier
                                .focusRequester(descFocus)
                                .focusProperties {
                                    up = backFocus
                                    down = playFocus
                                },
                        )
                        if (showFullDesc) {
                            FullDescriptionDialog(
                                title = video.title,
                                description = desc,
                                onDismiss = { showFullDesc = false },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // 播放信息(清晰度/选集):解析结果到达后展示;未到达/失败时不显示
                    // 占位行,点播放仍由播放页自行解析(不阻塞主操作)。
                    val detailPlayback = remember(playbackInfo, prefetchMaxQn, resumePage) {
                        playbackInfo?.let { detailPlaybackUi(it, prefetchMaxQn, resumePage) }
                    }
                    val qualityOptions = detailPlayback?.qualityOptions.orEmpty()
                    val totalPages = detailPlayback?.totalPages ?: 0
                    val hasEpisodes = totalPages > 1
                    val currentPage = (selectedPageOverride ?: detailPlayback?.pageIndex ?: 0)
                        .coerceIn(0, maxOf(totalPages - 1, 0))
                    val currentQualityQn = selectedQualityQn ?: detailPlayback?.currentQualityQn
                    // 手选清晰度后按钮文案要跟随选择(否则选了 720P 仍显示自动档的 360P)。
                    val currentQualityLabel = qualityOptions.firstOrNull { it.qn == currentQualityQn }?.label
                        ?: detailPlayback?.currentQualityLabel

                    // 主操作(播放)获得进入时焦点：简介溢出时上方还有"查看完整简介"按钮，
                    // 不指定焦点路径遥控器会先落在那里，多按一次才能到播放。
                    LaunchedEffect(video.id) { playFocus.requestFocus() }
                    TvActionButton(
                        label = buildPlayLabel(hintText != null, currentQualityLabel),
                        primary = true,
                        onClick = {
                            // 登记观看历史(继续观看行):元数据齐全,保留上次观看页;位置由
                            // 播放器退出时回写。provider 取点击时刻的当前源,首页继续观看行
                            // 按当前源过滤展示。
                            // 写盘放应用级 IO 作用域:onClick 在主线程,SP + JSON 编解码不能
                            // 阻塞首帧;页面随即跳走,不等待写入完成。
                            val entry = WatchEntry(
                                videoId = video.id,
                                provider = repository.currentProvider,
                                title = video.title,
                                thumbnailUrl = video.thumbnailUrl.orEmpty(),
                                durationSeconds = video.durationSeconds ?: 0L,
                                page = currentPage,
                            )
                            BackgroundWork.scope.launch { WatchHistory.upsert(context, entry) }
                            onPlay(currentPage, selectedQualityQn)
                        },
                        // TV 详情主操作固定宽度:铺满右栏会变成一条横贯屏幕的蓝带,
                        // 压过封面与标题的视觉重心;向上方向显式打通至披露按钮或返回键,
                        // 向下进入清晰度/选集行(没有时保持默认焦点行为)。
                        modifier = Modifier
                            .width(300.dp)
                            .focusRequester(playFocus)
                            .focusProperties {
                                up = if (descOverflowed) descFocus else backFocus
                                if (qualityOptions.isNotEmpty()) {
                                    down = firstQualityFocus
                                } else if (hasEpisodes) {
                                    down = firstEpisodeFocus
                                }
                            },
                    )
                    // 清晰度/选集用「行内小标签 + chips」的紧凑排版:1080p 电视上
                    // 播放按钮下方只有约 230px 可视高度,两段都带独立标题行会把选集
                    // 推到首屏外(用户体感仍是"详情页没有剧集选择")。
                    if (qualityOptions.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "清晰度",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                // 与选集行同宽:两行 chips 左边缘对齐,不出现阶梯错位。
                                modifier = Modifier.width(112.dp),
                            )
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(qualityOptions, key = { _, quality -> quality.qn }) { index, quality ->
                                    TvChip(
                                        label = quality.label,
                                        selected = quality.qn == currentQualityQn,
                                        onClick = { selectedQualityQn = quality.qn },
                                        modifier = if (index == 0) {
                                            Modifier.focusRequester(firstQualityFocus)
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (hasEpisodes) {
                        Spacer(Modifier.height(6.dp))
                        // 上一集/下一集在边界时不渲染;焦点固定给第一个出现的动作,
                        // 播放按钮 DOWN 才能稳定落到选集行。
                        val episodeActions = buildList<Pair<String, () -> Unit>> {
                            if (currentPage > 0) {
                                add("上一集" to { selectedPageOverride = currentPage - 1 })
                            }
                            if (currentPage < totalPages - 1) {
                                add("下一集" to { selectedPageOverride = currentPage + 1 })
                            }
                            add("查看全部" to { showEpisodeDialog = true })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "选集 ${currentPage + 1}/${totalPages}",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.width(112.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                episodeActions.forEachIndexed { index, (label, action) ->
                                    TvChip(
                                        label = label,
                                        onClick = action,
                                        modifier = if (index == 0) {
                                            Modifier.focusRequester(firstEpisodeFocus)
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (showEpisodeDialog && detailPlayback != null) {
                        EpisodeSelectDialog(
                            pageTitles = detailPlayback.pageTitles,
                            totalPages = detailPlayback.totalPages,
                            currentPage = currentPage,
                            onSelect = { index ->
                                selectedPageOverride = index
                                showEpisodeDialog = false
                            },
                            onDismiss = { showEpisodeDialog = false },
                        )
                    }
                }
            }
        }
    }
}

/** 播放按钮文案:基础动作 + 当前清晰度(与 legacy 详情页口径一致,未解析到清晰度时只显示动作)。 */
private fun buildPlayLabel(resume: Boolean, qualityLabel: String?): String = buildString {
    append(if (resume) "继续播放" else "播放")
    qualityLabel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
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
            TvDialogButton(
                label = "关闭",
                primary = true,
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocus),
            )
        },
        containerColor = CardGray,
    )
}
