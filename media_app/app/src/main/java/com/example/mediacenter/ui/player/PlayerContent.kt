package com.example.mediacenter.ui.player

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.data.playbackStorageKey
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.player.PlaybackService
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.util.NetworkRestoreEffect
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
    // 当前流:YouTube 的选项带 mimeType(HLS m3u8 无扩展名,必须显式声明
    // 才能命中 HLS 媒体源;progressive mp4 靠 URL 推断也可,统一带上最稳)。
    val stream = playbackResult.options.first()
    var showControls by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    // BACK 先收起控制条,再退播放页(标准视频播放器行为);收起状态由
    // PlayerScreen 的 BackHandler 兜底退出。Compose 中后注册的可用
    // BackHandler 优先,故此处注册即获得优先级。
    // 错误层(playerError 全屏 / overlayError 盖层)替换了控制条渲染,此时
    // 收起控制条无任何视觉反馈:禁用本 handler,BACK 直接走兜底退出。
    BackHandler(enabled = showControls && playerError == null && overlayError == null) { showControls = false }
    var isPlaying by remember { mutableStateOf(false) }
    // 缓冲中提示：卡顿不再表现为“画面冻住不知道在干嘛”
    var isBuffering by remember { mutableStateOf(false) }
    // 最近一次遥控器按键时间：控制条显示期间有任何操作都重置自动隐藏计时
    var lastInteractionMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 重试计数:URL 未变(签名地址相同)时也能强制重走加载流程
    var loadEpoch by remember { mutableIntStateOf(0) }
    // 续播标志:新流 READY 后尝试恢复断点续播(真实位置在 READY 时读取,此时才有时长)
    var shouldResume by remember { mutableStateOf(false) }
    // 换源保进度:重载前捕获的精确播放位置(内存传递,不受 PlaybackPositionStore
    // 的 30s 下限/5s 持久化粒度限制)。同分P内换清晰度/播放错误重试时携位续播;
    // 跨分P是不同内容,位置必须清零。READY 后无条件 seekTo 该值。
    var pendingSeekMs by remember { mutableLongStateOf(0L) }
    // 上次加载流所属的分P,用于判定"换清晰度"与"换分P"
    var lastPage by remember { mutableIntStateOf(playbackResult.pages.currentPage) }
    val controlsFocus = remember { FocusRequester() }
    // 多P视频的分P位置不能跨流恢复,只在单P视频上启用断点续播
    val canResume = playbackResult.pages.totalPages <= 1
    // 播放错误自动重试:签名地址过期/网络抖动最常见,首次错误自动重载一次
    // (loadEpoch 驱动,同分P携位续播);再错才显示错误层交手动重试,防无限
    // 循环。READY 即播放成功,重置计数。
    var playerAutoRetries by remember { mutableIntStateOf(0) }
    // READY 回调里的断点读取(SP + JSON 解码)经它移到 IO 线程执行
    val scope = rememberCoroutineScope()

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
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // 播放成功:自动重试预算重置
                    playerAutoRetries = 0
                    if (pendingSeekMs > 0L) {
                        // 换源保进度:精确接续优先于断点续播(多P视频换清晰度也走这里)
                        val target = pendingSeekMs
                        pendingSeekMs = 0L
                        controller?.seekTo(target)
                    } else if (shouldResume) {
                        shouldResume = false
                        val durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
                        // 断点读取含 SharedPreferences + JSON 解码,READY 回调在主线程;
                        // 移 IO 读后回主线程 seek(DetailScreen 同款模式)。页面已退出时
                        // controller 已释放,seekTo 的异常吞掉即可。
                        scope.launch {
                            val seekTo = withContext(Dispatchers.IO) {
                                PlaybackPositionStore.resumePosition(context, videoId, durationMs)
                            }
                            if (seekTo > 0) runCatching { controller?.seekTo(seekTo) }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playerAutoRetries < 1) {
                    // 首次错误自动重载一次:loadEpoch 驱动加载 effect 重走,
                    // 同分P携位续播;playerError 由加载 effect 清空。
                    playerAutoRetries++
                    loadEpoch++
                    return
                }
                playerError = friendlyPlaybackError(error)
            }
        }
    }
    DisposableEffect(controller) {
        controller?.addListener(playerListener)
        onDispose {
            // 退出播放页前保存位置,保证"播放中途返回"也能续播。
            // 多P视频按页隔离存储(key 带 #pN),各页位置互不覆盖。
            val posMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val durMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            val pagesInfo = playbackResult.pages
            PlaybackPositionStore.save(context, playbackStorageKey(videoId, pagesInfo.currentPage), posMs)
            // 回写观看历史(继续观看行):看完(距结尾不足 15s)的条目会被移除;
            // 多P回写绝对位置(前面各页时长累加)+ 最后观看页,继续观看点击后直达该页
            val offsetSec = pagesInfo.pageDurations.take(pagesInfo.currentPage).sum()
            val totalPageSec = pagesInfo.pageDurations.sum()
            WatchHistory.updatePosition(
                context,
                videoId,
                offsetSec + posMs / 1000,
                if (totalPageSec > 0) totalPageSec else durMs / 1000,
                page = pagesInfo.currentPage,
            )
            controller?.removeListener(playerListener)
            releaseOnce(controller)
            // 本 App 无后台播放需求:页面退出即停止播放服务
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // stream 变化(切清晰度/分P)或重试时加载流;controller 连接成功后才会真正执行
    LaunchedEffect(controller, stream, loadEpoch) {
        val c = controller ?: return@LaunchedEffect
        playerError = null
        // 换源保进度:同分P重载前捕获精确位置;stop 后位置归零,捕获值经
        // pendingSeekMs 传递到 READY 回调。首次加载时 controller 位置为 0,
        // 走原有断点续播流程;跨分P强制归零(不同内容不能携位)。
        val samePage = playbackResult.pages.currentPage == lastPage
        lastPage = playbackResult.pages.currentPage
        val captured = if (samePage) {
            runCatching { c.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        } else {
            0L
        }
        pendingSeekMs = when {
            captured > 0L -> captured
            samePage -> pendingSeekMs
            else -> {
                // 跨分P:恢复目标页的持久化断点(此前多P换页完全不恢复)。
                // 时长未知,不做"已看完"过滤;无记录时为 0,等同全新开始。
                val pageKey = playbackStorageKey(videoId, playbackResult.pages.currentPage)
                PlaybackPositionStore.resumePosition(context, pageKey)
            }
        }
        // 多P按页直达(继续观看)首次加载:同分P场景下 captured 与 pendingSeek
        // 均为 0,这里补读目标页的持久化断点(首页 key 即原 videoId),否则打开
        // 即在页首,与"上次看到第 N 页 X 分"的详情页提示不一致
        if (!canResume && pendingSeekMs <= 0L) {
            val stored = PlaybackPositionStore.resumePosition(
                context,
                playbackStorageKey(videoId, playbackResult.pages.currentPage),
            )
            if (stored > 0L) pendingSeekMs = stored
        }
        // 单P视频:无精确接续位置时,新流 READY 后尝试恢复断点续播
        shouldResume = canResume && pendingSeekMs <= 0L
        c.stop()
        c.clearMediaItems()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .build()
        c.setMediaItem(item)
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
    // 暂停且控制条隐藏时才完全停止轮询;暂停但控制条可见时继续刷新——
    // 否则遥控器按键 seek 后进度条/时间纹丝不动,用户得不到任何反馈。
    // 覆盖层隐藏且播放中时跳过 positionMs/durationMs 写入:这两个状态只被覆盖层消费,
    // 隐藏时无读者,写了也是无效开销(持久化照常,崩溃恢复不依赖 UI 可见)。
    // 换分P必须重启轮询:playbackResult 捕获进协程,不重启则换页后仍按旧页
    // 持久化(key 带旧 #pN),恢复位置错乱。
    LaunchedEffect(isPlaying, showControls, playbackResult.pages.currentPage) {
        if (!isPlaying && !showControls) {
            // 暂停瞬间刷一次位置 + 时长用于下次显示，之后不再轮询。
            positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            val pos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val dur = controller?.duration?.coerceAtLeast(0L) ?: 0L
            if (showControls) {
                positionMs = pos
                durationMs = dur
            }
            tick++
            if (isPlaying && tick % 10 == 0) {
                // 与退出保存/恢复加载同一 key(多P带 #pN 页码):裸 videoId 会把
                // 当前页位置写进首页 key,不带页码重进时按首页时长错误 seek。
                PlaybackPositionStore.save(
                    context,
                    playbackStorageKey(videoId, playbackResult.pages.currentPage),
                    pos,
                )
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
    // 音频焦点:controller 就绪后才抢占——此前进入页面即抢占,连接/解析失败时
    // 白白压低其他 App 的音量;被系统拒绝时记日志继续(用户可感知冲突自行处理)。
    // controller 重连(release+新建)时旧焦点随 onDispose 归还、新 controller 再抢占。
    DisposableEffect(controller) {
        if (controller != null) {
            // minSdk 23 兼容:只能用旧版 AudioFocus API(API 26+ 的 AudioFocusRequest 不可用)
            @Suppress("DEPRECATION")
            val granted = audioManager.requestAudioFocus(
                audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            )
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AppLog.w("Player", "audio focus not granted (result=$granted); may compete with other apps")
            }
        }
        onDispose {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    // 播放中自动隐藏控制条；显示期间任何按键(浏览分P/清晰度等)都会通过
    // lastInteractionMs 重启本 effect，重新计满 4s——不会在操作中途突然消失。
    LaunchedEffect(showControls, isPlaying, lastInteractionMs) {
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
            autoFocusRetry = true,
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
                    lastInteractionMs = System.currentTimeMillis()
                    return@onPreviewKeyEvent true
                }
                if (event.key in CONTROL_KEYS && showControls) {
                    // 控制条已显示：本次按键用于操作，重置自动隐藏计时
                    lastInteractionMs = System.currentTimeMillis()
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
            // 视图移除时解绑 player 引用(controller 已在别处 release,防悬挂引用)
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // 缓冲指示：画面未停但流没跟上时给出“正在加载”反馈
        if (isBuffering) {
            LoadingIndicator(modifier = Modifier.fillMaxSize())
        }
        // 控制条与解析失败错误层互斥渲染：错误层盖在控制条上时，
        // 焦点可能落在被遮挡的控件上（看不见却拿到焦点）。
        if (overlayError != null) {
            ErrorView(
                message = overlayError,
                onRetry = onRetryResolve,
                autoFocusRetry = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (showControls) {
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
    }
}
