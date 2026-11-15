package com.example.mediacenter.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.util.BackgroundWork
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    videoId: String,
    videoTitle: String,
    initialPage: Int = 0,
    onBack: () -> Unit,
    // 合集自动连播:导航到下一集(替换当前播放页,返回时回到详情/首页)
    onNextVideo: (VideoSummary) -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_${videoId}_p$initialPage",
        factory = PlayerViewModelFactory(videoId, initialPage),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    // 合集下一集由 ViewModel 发出事件,UI 只做"登记历史 + 导航"。
    LaunchedEffect(viewModel, context) {
        viewModel.episodeEvents.collect { nextEpisode ->
            val entry = WatchEntry(
                videoId = nextEpisode.id,
                provider = MediaRepositoryHolder.repository.currentProvider,
                title = nextEpisode.title,
                thumbnailUrl = nextEpisode.thumbnailUrl.orEmpty(),
                durationSeconds = nextEpisode.durationSeconds ?: 0L,
                page = 0,
            )
            // 登记历史是 SP + JSON 写盘,移出主线程;导航不等待它完成。
            BackgroundWork.scope.launch { WatchHistory.upsert(context, entry) }
            onNextVideo(nextEpisode)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        val result = state.playbackResult
        if (result != null) {
            val url = result.options.firstOrNull()?.url
            if (url != null) {
                PlayerContent(
                    videoId = videoId,
                    videoTitle = videoTitle,
                    playbackResult = result,
                    collection = state.collection,
                    overlayError = state.error,
                    isLoading = state.isLoading,
                    onSelectQuality = viewModel::selectQuality,
                    onSelectPage = viewModel::selectPage,
                    onRetryResolve = viewModel::resolvePlayback,
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
            // 首次解析(尤其 YouTube 冷解析)可能持续数秒,给一句明确状态而不是裸转圈。
            state.isLoading && result == null -> LoadingIndicator(message = "正在解析播放地址…")
            state.error != null && result == null -> ErrorView(
                message = state.error!!, autoFocusRetry = true,
                onRetry = { viewModel.resolvePlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
