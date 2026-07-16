### Task 5: PlayerScreen — 播放器

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/player/PlayerScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/player/PlayerViewModel.kt`
- Modify: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt`

**Interfaces:**
- Consumes: `MediaRepository`, `BilibiliApi`, `CappedLoadErrorHandlingPolicy`, `VideoSummary`
- Produces: PlayerScreen Composable 入口

- [ ] **Step 1: 写 PlayerViewModel**

```kotlin
package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.BilibiliApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playbackOption: PlaybackOption? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val videoId: String,
    private val repository: MediaRepository = MediaRepository(),
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init { resolvePlayback() }

    fun resolvePlayback() {
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)
            try {
                val option = bilibiliApi.resolvePlaybackUrl(videoId)
                _uiState.value = PlayerUiState(playbackOption = option, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = PlayerUiState(isLoading = false, error = e.message)
            }
        }
    }
}
```

- [ ] **Step 2: 写 PlayerScreen.kt**

使用 `AndroidView` 包裹 ExoPlayer（Pure Compose 的 Media3 集成不成熟，AndroidView 是最稳定的方案）。

```kotlin
package com.example.mediacenter.ui.player

import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.ui.theme.BackgroundDark

@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "player_$videoId",
        factory = PlayerViewModelFactory(videoId),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        if (state.isLoading) {
            LoadingIndicator()
        } else if (state.playbackOption != null) {
            var player by remember { mutableStateOf<ExoPlayer?>(null) }
            DisposableEffect(videoId) {
                val ctx = LocalContext.current
                val p = ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(ctx)
                            .setLoadErrorHandlingPolicy(CappedLoadErrorHandlingPolicy())
                    )
                    .build()
                p.setMediaItem(MediaItem.fromUri(state.playbackOption!!.url))
                p.prepare()
                p.play()
                player = p
                onDispose { p.release() }
            }
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = this@PlayerScreen.player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.error != null) {
            ErrorView(state.error!!, onRetry = { viewModel.resolvePlayback() })
        }
    }
}
```

- [ ] **Step 3: 注册到 AppNavigation.kt**

添加 PlayerScreen 路由。

- [ ] **Step 4: 编译验证 + Commit**

---

