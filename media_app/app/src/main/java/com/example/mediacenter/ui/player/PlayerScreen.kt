package com.example.mediacenter.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.PlaybackPrefetcher
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.util.BackgroundWork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 下一集预取延迟:太早会为"看几秒就退出"的视频浪费解析,15s 后仍在播放说明大概率会看完。 */
private const val NEXT_PREFETCH_DELAY_MS = 15_000L

/**
 * 首次解析等待提示:前 3s 只显示状态文案,之后每秒追加已等待秒数。
 * YouTube 冷解析可达 25-30s,用户需要知道"没卡死,仍在解析"。
 */
@Composable
private fun ResolvingPlaybackIndicator() {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsedSeconds += 1
        }
    }
    val message = if (elapsedSeconds >= 3) {
        "正在解析播放地址… 已等待 ${elapsedSeconds}s"
    } else {
        "正在解析播放地址…"
    }
    LoadingIndicator(message = message)
}

@Composable
fun PlayerScreen(
    videoId: String,
    videoTitle: String,
    initialPage: Int = 0,
    // 首帧前的封面(详情尺寸);为空则不显示封面层。
    posterUrl: String? = null,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_${videoId}_p$initialPage",
        factory = PlayerViewModelFactory(videoId, initialPage, videoTitle, posterUrl),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    // 片尾连播预热:播放页停留 15s 后预取下一集播放地址(见 PlaybackPrefetcher),
    // 自动/手动切集直接命中,不用再等解析;离开页面或下一集变化时协程自动取消。
    // 用 state.videoId 而非路由参数:合集无缝切集后,预取目标要跟随当前集。
    val nextPrefetchTarget = remember(state.next, state.videoId) {
        nextPrefetchTargetId(
            state.videoId,
            MediaRepositoryHolder.repository.currentProvider,
            state.next,
        )
    }
    LaunchedEffect(nextPrefetchTarget) {
        val targetId = nextPrefetchTarget ?: return@LaunchedEffect
        delay(NEXT_PREFETCH_DELAY_MS)
        PlaybackPrefetcher.prefetch(MediaRepositoryHolder.repository, targetId)
    }

    // 合集无缝切集:新一集登记观看历史(首页"继续观看"可见),不阻塞切集。
    // 首集已在详情/首页点击时登记过,这里只处理页内切换后的新集。
    LaunchedEffect(state.videoId) {
        if (state.videoId.isBlank() || state.videoId == videoId) return@LaunchedEffect
        val entry = WatchEntry(
            videoId = state.videoId,
            provider = MediaRepositoryHolder.repository.currentProvider,
            title = state.title,
            thumbnailUrl = state.posterUrl.orEmpty(),
            durationSeconds = state.durationSeconds ?: 0L,
            page = 0,
        )
        // 登记历史是 SP + JSON 写盘,移出主线程;切集不等待它完成。
        BackgroundWork.scope.launch { WatchHistory.upsert(context, entry) }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        val result = state.playbackResult
        if (result != null) {
            val url = result.options.firstOrNull()?.url
            if (url != null) {
                PlayerContent(
                    // 用 state 中的当前集:合集无缝切集时播放页/服务不重建,只换流。
                    videoId = state.videoId,
                    videoTitle = state.title,
                    posterUrl = state.posterUrl,
                    playbackResult = result,
                    collection = state.collection,
                    overlayError = state.error,
                    isLoading = state.isLoading,
                    onSelectQuality = viewModel::selectQuality,
                    onSelectPage = viewModel::selectPage,
                    onRetryResolve = viewModel::resolvePlayback,
                    onQualityFallback = { viewModel.fallbackToLowerQuality() },
                    onBack = onBack,
                    hasAutoNext = state.next != null,
                    onAutoNext = { viewModel.onPlaybackEnded() },
                    onPrevious = { viewModel.playPreviousManual() },
                    onNext = { viewModel.playNextManual() },
                )
            } else {
                ErrorView(
                    message = "无可用播放地址",
                    onRetry = viewModel::resolvePlayback,
                    modifier = Modifier.fillMaxSize(),
                    // 全屏错误层必须有明确的遥控器焦点落点(与首页一致)
                    autoFocusRetry = true,
                )
            }
        }
        when {
            // 首次解析(尤其 YouTube 冷解析)可能持续数秒到数十秒,给一句明确状态
            // 而不是裸转圈;等待超过 3s 后追加已等待秒数,避免用户以为卡死。
            state.isLoading && result == null -> ResolvingPlaybackIndicator()
            state.error != null && result == null -> ErrorView(
                message = state.error!!, autoFocusRetry = true,
                onRetry = { viewModel.resolvePlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
