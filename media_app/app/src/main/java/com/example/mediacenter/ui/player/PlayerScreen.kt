package com.example.mediacenter.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.QualityOption
import com.example.mediacenter.player.PlaybackService
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.BackgroundDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.UiIcons
import com.example.mediacenter.util.NetworkRestoreEffect
import com.example.mediacenter.util.formatDuration
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun PlayerScreen(
    videoId: String,
    videoTitle: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        key = "player_$videoId",
        factory = PlayerViewModelFactory(videoId),
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
                    "无可用播放地址",
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

@Composable
private fun PlayerContent(
    videoId: String,
    videoTitle: String,
    playbackResult: PlaybackResult,
    overlayError: String?,
    onSelectQuality: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onRetryResolve: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val url = playbackResult.options.first().url
    var showControls by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 重试计数:URL 未变(签名地址相同)时也能强制重走加载流程
    var loadEpoch by remember { mutableIntStateOf(0) }
    // 续播标志:新流 READY 后尝试恢复断点续播(真实位置在 READY 时读取,此时才有时长)
    var shouldResume by remember { mutableStateOf(false) }
    val controlsFocus = remember { FocusRequester() }
    // 多P视频的分P位置不能跨流恢复,只在单P视频上启用断点续播
    val canResume = playbackResult.pages.totalPages <= 1

    // 与 PlaybackService 里的播放器异步建立 MediaController 连接。
    // 系统媒体栏/通知/媒体键由服务端 MediaSession 承载,页面只负责驱动。
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controller = try {
            suspendCancellableCoroutine { continuation ->
                val onReady = {
                    val mediaController = runCatching { future.get() }.getOrNull()
                    if (continuation.isActive) {
                        continuation.resume(mediaController)
                    } else {
                        // 页面已退出(协程取消),连接结果无人消费,立即释放避免泄漏
                        mediaController?.release()
                    }
                    Unit
                }
                continuation.invokeOnCancellation {
                    // 协程取消时若连接已完成,直接释放
                    if (future.isDone) {
                        runCatching { future.get().release() }
                    }
                }
                future.addListener(onReady, Executor { command -> command.run() })
            }
        } catch (e: Exception) {
            controllerFailed = true
            null
        }
    }

    // 播放器 listener 必须先于加载流程注册:本地/强缓存流极快 READY 时也不会错过
    // 断点续播 seek。恢复位置在 READY 回调里读取,此时才拿得到真实时长做"已看完"过滤。
    val playerListener = remember {
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && shouldResume) {
                    shouldResume = false
                    val durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
                    val seekTo = PlaybackPositionStore.resumePosition(context, videoId, durationMs)
                    if (seekTo > 0) controller?.seekTo(seekTo)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = friendlyPlaybackError(error)
            }
        }
    }
    DisposableEffect(controller) {
        controller?.addListener(playerListener)
        onDispose {
            // 退出播放页前保存位置,保证"播放中途返回"也能续播
            PlaybackPositionStore.save(context, videoId, controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            controller?.removeListener(playerListener)
            controller?.release()
            // 本 App 无后台播放需求:页面退出即停止播放服务
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // url 变化(切清晰度/分P)或重试时加载流;controller 连接成功后才会真正执行
    LaunchedEffect(controller, url, loadEpoch) {
        val c = controller ?: return@LaunchedEffect
        playerError = null
        // 单P视频:新流 READY 后尝试恢复断点续播(多P分页位置不能跨流恢复)
        shouldResume = canResume
        c.stop()
        c.clearMediaItems()
        c.setMediaItem(MediaItem.fromUri(url))
        c.prepare()
        c.play()
    }

    // 解析失败(切清晰度/分P)时暂停旧流,避免覆盖层下声音继续
    LaunchedEffect(overlayError) {
        if (overlayError != null) controller?.pause()
    }

    // 进度轮询;播放中每 5 秒持久化一次,崩溃/杀进程也能恢复
    LaunchedEffect(isPlaying) {
        var tick = 0
        while (true) {
            positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            tick++
            if (isPlaying && tick % 10 == 0) {
                PlaybackPositionStore.save(context, videoId, positionMs)
            }
            delay(500)
        }
    }

    // 音频焦点:抢占时暂停、归还后恢复,兼顾来电/其他 App 播放
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var resumeAfterTransient by remember { mutableStateOf(false) }
    val audioFocusListener = remember {
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> controller?.pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterTransient = controller?.isPlaying == true
                    controller?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> controller?.setVolume(0.3f)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    controller?.setVolume(1f)
                    if (resumeAfterTransient) {
                        resumeAfterTransient = false
                        controller?.play()
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        // minSdk 23 兼容:只能用旧版 AudioFocus API(API 26+ 的 AudioFocusRequest 不可用)
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        )
        onDispose {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    // 播放中自动隐藏控制条
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(AUTO_HIDE_DELAY_MS)
            showControls = false
        }
    }
    // 控制条显示时聚焦默认按钮
    LaunchedEffect(showControls) {
        if (showControls) controlsFocus.requestFocus()
    }

    // 断网恢复:播放错误源于网络时,网络可用后自动重试一次(解析 + 重新加载)
    NetworkRestoreEffect(onRestored = {
        if (playerError != null) {
            playerError = null
            loadEpoch++
            onRetryResolve()
        }
    })

    // 服务连接失败 / 未就绪时给出明确反馈
    if (controllerFailed) {
        ErrorView(
            message = "播放组件启动失败，请返回重试",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    if (controller == null) {
        LoadingIndicator()
        return
    }

    if (playerError != null) {
        ErrorView(
            message = playerError!!,
            onRetry = {
                playerError = null
                loadEpoch++
                onRetryResolve()
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key in CONTROL_KEYS && !showControls) {
                    showControls = true
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                }
            },
            update = { view -> view.player = controller },
            modifier = Modifier.fillMaxSize(),
        )
        if (showControls) {
            ControlsOverlay(
                videoTitle = videoTitle,
                playbackResult = playbackResult,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                controlsFocus = controlsFocus,
                onTogglePlay = {
                    if (controller?.isPlaying == true) controller?.pause() else controller?.play()
                },
                onSeek = { controller?.seekTo(it) },
                onSelectQuality = onSelectQuality,
                onSelectPage = onSelectPage,
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 解析失败覆盖层(切清晰度/分P失败,旧流已暂停,不影响画面)
        if (overlayError != null) {
            ErrorView(
                message = overlayError,
                onRetry = onRetryResolve,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ControlsOverlay(
    videoTitle: String,
    playbackResult: PlaybackResult,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    controlsFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQuality: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentQn = playbackResult.options.firstOrNull()?.qn
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.55f))) {
        // 顶部:标题 + 返回
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = videoTitle,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier.requiredSize(48.dp),
            ) {
                Icon(UiIcons.ArrowBack, contentDescription = "返回", tint = TextPrimary)
            }
        }
        // 底部:进度 + 播放控制 + 分P + 清晰度
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 拖动中的临时进度:拖动期间优先展示它,松手/按键时才真正 seek
            var dragPositionMs by remember { mutableStateOf<Float?>(null) }
            val sliderMax = durationMs.toFloat().coerceAtLeast(1f)
            // 焦点在 Slider 上时,左右键 = 快进/快退 10s(长按连续触发)
            Slider(
                value = (dragPositionMs ?: positionMs.toFloat()).coerceIn(0f, sliderMax),
                onValueChange = { value ->
                    dragPositionMs = value
                    onSeek(value.toLong())
                },
                onValueChangeFinished = { dragPositionMs = null },
                valueRange = 0f..sliderMax,
                modifier = Modifier
                    .weight(1f)
                    .requiredHeight(48.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key != Key.DirectionLeft && event.key != Key.DirectionRight) {
                            return@onPreviewKeyEvent false
                        }
                        val delta = if (event.key == Key.DirectionLeft) -SEEK_STEP_MS else SEEK_STEP_MS
                        onSeek((positionMs + delta).coerceIn(0L, durationMs.coerceAtLeast(0L)))
                        true
                    },
                colors = SliderDefaults.colors(
                    thumbColor = AppleBlue,
                    activeTrackColor = AppleBlue,
                    inactiveTrackColor = CardGray,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.requiredSize(48.dp).focusRequester(controlsFocus),
                ) {
                    Icon(
                        imageVector = if (isPlaying) UiIcons.Pause else UiIcons.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = TextPrimary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (playbackResult.pages.totalPages > 1) {
                    Text("分P", color = TextSecondary, fontSize = 14.sp)
                    playbackResult.pages.pageTitles.forEachIndexed { index, pageTitle ->
                        SelectChip(
                            label = if (pageTitle.isBlank()) "${index + 1}P" else "${index + 1}P ${pageTitle}",
                            selected = index == playbackResult.pages.currentPage,
                            onClick = { onSelectPage(index) },
                        )
                    }
                }
            }
            if (playbackResult.availableQualities.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("清晰度", color = TextSecondary, fontSize = 14.sp)
                    playbackResult.availableQualities.forEach { quality ->
                        SelectChip(
                            label = quality.label,
                            selected = quality.qn == currentQn,
                            onClick = { onSelectQuality(quality.qn) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.requiredHeight(40.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) AppleBlue else CardGray,
            focusedContainerColor = AppleBlue,
            contentColor = TextPrimary,
            focusedContentColor = TextPrimary,
        ),
    ) {
        Text(label, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
    }
}

private fun friendlyPlaybackError(error: PlaybackException): String = when {
    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "播放位置已过期，请重试"
    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "播放超时，请重试"
    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的视频格式"
    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
        "播放地址失效或网络异常，正在重新解析"
    error.errorCode in 6001..6010 -> "播放受保护内容失败"
    else -> "播放失败（${error.errorCodeName}）"
}

private val CONTROL_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
)

// 焦点在进度条上时左右键的 seek 步长
private const val SEEK_STEP_MS = 10_000L

private const val AUTO_HIDE_DELAY_MS = 4000L
