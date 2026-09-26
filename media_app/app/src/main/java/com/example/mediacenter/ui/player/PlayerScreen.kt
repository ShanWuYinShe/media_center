package com.example.mediacenter.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.BackgroundDark

@Composable
fun PlayerScreen(
    videoId: String,
    videoTitle: String,
    initialPage: Int = 0,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_${videoId}_p$initialPage",
        factory = PlayerViewModelFactory(videoId, initialPage),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        val result = state.playbackResult
        if (result != null) {
            val url = result.options.firstOrNull()?.url
            if (url != null) {
                PlayerContent(
                    videoId = videoId,
                    videoTitle = videoTitle,
                    playbackResult = result,
                    overlayError = state.error,
                    onSelectQuality = viewModel::selectQuality,
                    onSelectPage = viewModel::selectPage,
                    onRetryResolve = viewModel::resolvePlayback,
                    onBack = onBack,
                )
            } else {
                ErrorView(
                    message = "无可用播放地址",
                    onRetry = viewModel::resolvePlayback,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        when {
            state.isLoading && result == null -> LoadingIndicator()
            state.error != null && result == null -> ErrorView(
                message = state.error!!,
                onRetry = { viewModel.resolvePlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
