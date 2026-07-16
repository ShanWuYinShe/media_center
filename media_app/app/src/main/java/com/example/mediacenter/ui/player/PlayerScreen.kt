package com.example.mediacenter.ui.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.BackgroundDark

@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_$videoId",
        factory = PlayerViewModelFactory(videoId),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        when {
            state.isLoading -> LoadingIndicator()
            state.playbackResult != null -> {
                val url = state.playbackResult!!.options.firstOrNull()?.url
                if (url != null) {
                    ExoPlayerPlayerView(url)
                } else {
                    ErrorView("无可用播放地址", modifier = Modifier.fillMaxSize())
                }
            }
            state.error != null -> ErrorView(
                message = state.error!!,
                onRetry = { viewModel.resolvePlayback() },
            )
        }
    }
}

@Composable
private fun ExoPlayerPlayerView(url: String) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setLoadErrorHandlingPolicy(CappedLoadErrorHandlingPolicy(maxRetries = 2)),
            )
            .build()
            .also { p ->
                p.setMediaItem(MediaItem.fromUri(url))
                p.prepare()
                p.play()
            }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
