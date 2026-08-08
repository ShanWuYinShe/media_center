package com.example.mediacenter.ui.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.player.PlaybackService
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.util.NetworkRestoreEffect
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal fun PlayerContent(
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
    // 连接失败自动重试(最多 MAX_CONTROLLER_ATTEMPTS 次,间隔 2s),
    // 覆盖服务启动竞态/瞬时故障,避免用户只能返回重进。
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFailed by remember { mutableStateOf(false) }
    var connectAttempts by remember { mutableIntStateOf(0) }
    // MediaController 释放守卫:同一实例可能被"协程取消时 future 已完成"、
    // "连接完成但页面已退出(onReady 未消费)"与"DisposableEffect onDispose"
    // 三条路径各释放一次(MediaController.release 非幂等,重复释放有风险)。
    // releaseOnce 保证每代 controller 恰好释放一次;新 controller 接管时重新武装。
    val releaseGuard = remember { AtomicBoolean(false) }
    fun releaseOnce(c: MediaController?) {
        if (c != null && releaseGuard.compareAndSet(false, true)) {
            c.release()
        }
    }
    LaunchedEffect(connectAttempts) {
        if (connectAttempts > 0) {
            delay(CONTROLLER_RETRY_DELAY_MS)
        }
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        val connected = try {
            suspendCancellableCoroutine<MediaController?> { continuation ->
                val onReady = {
                    val mediaController = runCatching { future.get() }.getOrNull()
                    if (continuation.isActive) {
                        continuation.resume(mediaController)
                    } else {
                        // 页面已退出(协程取消),连接结果无人消费,立即释放避免泄漏
                        releaseOnce(mediaController)
                    }
                    Unit
                }
                continuation.invokeOnCancellation {
                    // 协程取消时若连接已完成,直接释放
                    if (future.isDone) {
                        runCatching { releaseOnce(future.get()) }
                    }
                }
                future.addListener(onReady, Executor { command -> command.run() })
            }
        } catch (e: Exception) {
            null
        }
        if (connected != null) {
            // 新 controller 接管:重新武装释放守卫,旧 controller 已在 onDispose 释放
            releaseGuard.set(false)
            controller = connected
        } else if (connectAttempts < MAX_CONTROLLER_ATTEMPTS) {
            // 未达上限:递增触发 LaunchedEffect 重启,进入下一轮重试
            connectAttempts++
        } else {
            controllerFailed = true
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
            releaseOnce(controller)
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

    // 解析失败(切清晰度/分P)时暂停旧流,避免覆盖层下声音继续;
    // 重试成功(overlayError 清空)后若 url 未变(加载 effect 不重启,极端情况
    // 下签名地址相同),显式恢复播放,避免视频停在暂停态无任何提示。
    LaunchedEffect(overlayError) {
        if (overlayError != null) {
            controller?.pause()
        } else if (controller?.playbackState == Player.STATE_READY) {
            controller?.play()
        }
    }

    // 进度轮询;播放中每 5 秒持久化一次,崩溃/杀进程也能恢复。
    // 暂停时停止轮询(每帧读 currentPosition + 写状态是无效开销)，恢复播放即重启本 effect。
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            // 暂停瞬间刷一次位置 + 时长用于覆盖层显示，之后不再轮询。
            positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            tick++
            if (tick % 10 == 0) {
                PlaybackPositionStore.save(context, videoId, positionMs)
            }
            delay(500)
        }
    }

    // 音频焦点:抢占时暂停、归还后恢复,兼顾来电/其他 App 播放
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var resumeAfterTransient by remember { mutableStateOf(false) }

    // Android 13+ 通知权限:首次进入播放页时请求(媒体通知/系统媒体栏才需要),
    // 不在 App 启动时弹窗打扰用户;拒绝后不影响播放,仅系统媒体栏不可见。
    LaunchedEffect(Unit) {
        val activity = context as? Activity
        if (activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

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
