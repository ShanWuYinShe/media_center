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
            WatchHistory.upsert(
                context,
                WatchEntry(
                    videoId = nextEpisode.id,
                    provider = MediaRepositoryHolder.repository.currentProvider,
                    title = nextEpisode.title,
                    thumbnailUrl = nextEpisode.thumbnailUrl.orEmpty(),
                    durationSeconds = nextEpisode.durationSeconds ?: 0L,
                    page = 0,
                ),
            )
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
            state.isLoading && result == null -> LoadingIndicator()
            state.error != null && result == null -> ErrorView(
                message = state.error!!, autoFocusRetry = true,
                onRetry = { viewModel.resolvePlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
