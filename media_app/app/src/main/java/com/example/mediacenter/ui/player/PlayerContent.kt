package com.example.mediacenter.ui.player

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.example.mediacenter.data.PlaybackPositionStore
import com.example.mediacenter.data.PlaybackResult
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.data.playbackStorageKey
import com.example.mediacenter.player.PlaybackMediaSourceFactory
import com.example.mediacenter.player.PlaybackService
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlayerContent(
    videoId: String,
    videoTitle: String,
    playbackResult: PlaybackResult,
    collection: PlaybackCollection?,
    overlayError: String?,
    onSelectQuality: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onRetryResolve: () -> Unit,
    onBack: () -> Unit,
    // 片尾自动连播:多P下一页优先,其次是合集队列的下一集(由 PlayerScreen 计算)
    hasAutoNext: Boolean,
    onAutoNext: () -> Unit,
    // 遥控器 ↑/↓ 手动切集
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    // 当前流:YouTube 的选项带 mimeType(HLS m3u8 无扩展名,必须显式声明
    // 才能命中 HLS 媒体源;progressive mp4 靠 URL 推断也可,统一带上最稳)。
    val stream = playbackResult.options.first()
    var showControls by remember { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    // 最近一次 Up/Down 切集时间：遥控器长按方向键会连发 KeyDown，
    // 不做节流会在几百毫秒内连跳好几集（老人误按率更高）。
    var lastEpisodeSwitchMs by remember { mutableLongStateOf(0L) }
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
    // 控制条隐藏时承接遥控器焦点:全局按键始终由播放器根节点处理，
    // 控制层只做信息展示，不抢焦点。
    val playerFocus = remember { FocusRequester() }
    // 多P视频的分P位置不能跨流恢复,只在单P视频上启用断点续播
    val canResume = playbackResult.pages.totalPages <= 1
    // 播放错误自动重试:签名地址过期/网络抖动最常见,首次错误自动重载一次
    // (loadEpoch 驱动,同分P携位续播);再错才显示错误层交手动重试,防无限
    // 循环。READY 即播放成功,重置计数。
    var playerAutoRetries by remember { mutableIntStateOf(0) }
    // 每个流(换P/换清晰度)只允许触发一次自动连播,避免 ENDED 重复回调导致连跳。
    var autoNextTriggered by remember { mutableStateOf(false) }
    val currentHasAutoNext by rememberUpdatedState(hasAutoNext)
    val currentOnAutoNext by rememberUpdatedState(onAutoNext)
    // READY 回调里的断点读取(SP + JSON 解码)经它移到 IO 线程执行
    val scope = rememberCoroutineScope()

    // 与 PlaybackService 里的播放器异步建立 MediaController 连接。
    // 系统媒体栏/通知/媒体键由服务端 MediaSession 承载,页面只负责驱动。
    // 连接失败自动重试(最多 MAX_CONTROLLER_ATTEMPTS 次,间隔 2s),
    // 覆盖服务启动竞态/瞬时故障,避免用户只能返回重进。
    val controllerState = rememberPlaybackController(context)
    val controller = controllerState.controller
    // listener 与页面级 onDispose 在 remember/DisposableEffect 中跨组合存活,
    // 直接捕获 controller 会永远拿到首次组合的 null。用 State 读取最新实例。
    val currentController by rememberUpdatedState(controller)
    // 播放器 listener 必须先于加载流程注册:本地/强缓存流极快 READY 时也不会错过
    // 断点续播 seek。恢复位置在 READY 回调里读取,此时才拿得到真实时长做"已看完"过滤。
    val playerListener = remember {
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && currentHasAutoNext && !autoNextTriggered) {
                    autoNextTriggered = true
                    currentOnAutoNext()
                }
                if (state == Player.STATE_READY) {
                    // 播放成功:自动重试预算重置
                    playerAutoRetries = 0
                    if (pendingSeekMs > 0L) {
                        // 换源保进度:精确接续优先于断点续播(多P视频换清晰度也走这里)
                        val target = pendingSeekMs
                        pendingSeekMs = 0L
                        currentController?.seekTo(target)
                    } else if (shouldResume) {
                        shouldResume = false
                        val durationMs = currentController?.duration?.coerceAtLeast(0L) ?: 0L
                        // 断点读取含 SharedPreferences + JSON 解码,READY 回调在主线程;
                        // 移 IO 读后回主线程 seek(DetailScreen 同款模式)。页面已退出时
                        // controller 已释放,seekTo 的异常吞掉即可。
                        scope.launch {
                            val seekTo = withContext(Dispatchers.IO) {
                                PlaybackPositionStore.resumePosition(context, videoId, durationMs)
                            }
                            if (seekTo > 0) runCatching { currentController?.seekTo(seekTo) }
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
    // 换流/换P后重新允许自动连播;同一流内 ENDED 只触发一次。
    LaunchedEffect(stream, playbackResult.pages.currentPage) {
        autoNextTriggered = false
    }
    // 合集队列是并行网络请求,极短视频可能在队列到达前就播完:队列晚到时
    // 如果播放器已处于 ENDED,补触发一次(否则用户停在最后一帧没有下一步)。
    LaunchedEffect(hasAutoNext, controller) {
        if (hasAutoNext && !autoNextTriggered && currentController?.playbackState == Player.STATE_ENDED) {
            autoNextTriggered = true
            onAutoNext()
        }
    }

    // 播放器参数要"随时读到最新值":分P切换只改 playbackResult,不会重建
    // 这个 effect;若闭包直接捕获参数,退出时会把位置写到首P的 key、并把
    // 首P的页码写进观看历史。
    val latestPlaybackResult by rememberUpdatedState(playbackResult)

    // listener 挂载/摘除只跟随 controller 实例。**必须捕获 c 而不是在
    // onDispose 里读 controller**:controller 由 null 变为实例时,旧 effect
    // 的 onDispose 会先执行,此时读 controller 拿到的是刚连上的新实例,
    // 会把它直接 release 掉(整个播放链路随之中断)。
    DisposableEffect(controller) {
        val c = controller
        c?.addListener(playerListener)
        onDispose {
            c?.removeListener(playerListener)
            // 实例被替换(非退出)时释放旧 controller;退出路径由下面的
            // Unit effect 统一保存位置并释放当前实例。
            if (c != null && c !== controller) {
                runCatching { c.release() }
            }
        }
    }

    // 页面级收尾:只在离开播放页时执行一次(Unit 不随 controller/分页变化)。
    DisposableEffect(Unit) {
        onDispose {
            // 退出播放页前保存位置,保证"播放中途返回"也能续播。
            // 多P视频按页隔离存储(key 带 #pN),各页位置互不覆盖。
            val c = currentController
            val posMs = c?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val durMs = c?.duration?.coerceAtLeast(0L) ?: 0L
            val pagesInfo = latestPlaybackResult.pages
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
            controllerState.release()
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
                // SP 首次访问会同步加载 XML,与 DetailScreen 同口径移 IO 线程读。
                val pageKey = playbackStorageKey(videoId, playbackResult.pages.currentPage)
                withContext(Dispatchers.IO) {
                    PlaybackPositionStore.resumePosition(context, pageKey)
                }
            }
        }
        // 多P按页直达(继续观看)首次加载:同分P场景下 captured 与 pendingSeek
        // 均为 0,这里补读目标页的持久化断点(首页 key 即原 videoId),否则打开
        // 即在页首,与"上次看到第 N 页 X 分"的详情页提示不一致
        if (!canResume && pendingSeekMs <= 0L) {
            val stored = withContext(Dispatchers.IO) {
                PlaybackPositionStore.resumePosition(
                    context,
                    playbackStorageKey(videoId, playbackResult.pages.currentPage),
                )
            }
            if (stored > 0L) pendingSeekMs = stored
        }
        // 单P视频:无精确接续位置时,新流 READY 后尝试恢复断点续播
        shouldResume = canResume && pendingSeekMs <= 0L
        c.stop()
        c.clearMediaItems()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .apply {
                // 音轨分离(YouTube 1080P+)与多段 durl(B 站老视频)所需的额外
                // 地址经 extras 传给 PlaybackService,由 PlaybackMediaSourceFactory
                // 在本地合成 Merging/Concatenating MediaSource
                // (MediaController 不支持直接传 MediaSource)。
                val extras = Bundle()
                stream.audioUrl?.takeIf { it.isNotBlank() }?.let { audioUrl ->
                    extras.putString(PlaybackMediaSourceFactory.EXTRA_AUDIO_URL, audioUrl)
                    stream.audioMimeType?.takeIf { it.isNotBlank() }?.let {
                        extras.putString(PlaybackMediaSourceFactory.EXTRA_AUDIO_MIME, it)
                    }
                }
                stream.segments?.takeIf { it.size > 1 }?.let { segments ->
                    extras.putStringArrayList(
                        PlaybackMediaSourceFactory.EXTRA_SEGMENTS,
                        ArrayList(segments),
                    )
                }
                if (extras.size() > 0) {
                    setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
                }
            }
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    // 解析失败(切清晰度/分P)时暂停旧流,避免覆盖层下声音继续;
    // 重试成功(overlayError 清空)后若 url 未变(加载 effect 不重启,极端情况
    // 下签名地址相同),显式恢复播放,避免视频停在暂停态无任何提示。
    LaunchedEffect(overlayError, controller) {
        if (overlayError != null) {
            currentController?.pause()
        } else if (currentController?.playbackState == Player.STATE_READY) {
            currentController?.play()
        }
    }

    // 进度轮询;播放中每 5 秒持久化一次,崩溃/杀进程也能恢复。
    // 暂停且控制条隐藏时才完全停止轮询;暂停但控制条可见时继续刷新——
    // 否则遥控器按键 seek 后进度条/时间纹丝不动,用户得不到任何反馈。
    // 覆盖层隐藏且播放中时跳过 positionMs/durationMs 写入:这两个状态只被覆盖层消费,
    // 隐藏时无读者,写了也是无效开销(持久化照常,崩溃恢复不依赖 UI 可见)。
    // 换分P必须重启轮询:playbackResult 捕获进协程,不重启则换页后仍按旧页
    // 持久化(key 带旧 #pN),恢复位置错乱。
    LaunchedEffect(isPlaying, showControls, playbackResult.pages.currentPage, controller) {
        if (!isPlaying && !showControls) {
            // 暂停瞬间刷一次位置 + 时长用于下次显示，之后不再轮询。
            positionMs = currentController?.currentPosition?.coerceAtLeast(0L) ?: 0L
            durationMs = currentController?.duration?.coerceAtLeast(0L) ?: 0L
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            val pos = currentController?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val dur = currentController?.duration?.coerceAtLeast(0L) ?: 0L
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
            // 每 30s 同步一次"继续观看"行:进程被杀时退出回写不会执行,
            // 只靠退出保存会让首页进度长期停在打开播放页之前的状态。
            if (isPlaying && tick % 60 == 0) {
                val pages = playbackResult.pages
                val offsetSec = pages.pageDurations.take(pages.currentPage).sum()
                val totalSec = pages.pageDurations.sum()
                WatchHistory.updatePosition(
                    context,
                    videoId,
                    offsetSec + pos / 1000,
                    if (totalSec > 0) totalSec else (dur / 1000),
                    page = pages.currentPage,
                )
            }
            delay(500)
        }
    }

    PlayerAudioFocus(controller)
    PlayerNotificationPermission()

    // 播放中自动隐藏控制条；显示期间任何按键(浏览分P/清晰度等)都会通过
    // lastInteractionMs 重启本 effect，重新计满 4s——不会在操作中途突然消失。
    LaunchedEffect(showControls, isPlaying, lastInteractionMs, showOptions) {
        if (showControls && isPlaying && !showOptions) {
            delay(AUTO_HIDE_DELAY_MS)
            showControls = false
        }
    }
    // 焦点只交给播放器根节点：控制层没有可聚焦的播放/快进按钮，方向键
    // 始终是全局操作，不会出现"先移动焦点再按 OK"的多余步骤。
    LaunchedEffect(controller, overlayError, playerError) {
        if (controller == null || overlayError != null || playerError != null) return@LaunchedEffect
        runCatching { playerFocus.requestFocus() }
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
    if (controllerState.failed) {
        ErrorView(
            message = "播放组件启动失败",
            autoFocusRetry = true,
            onRetry = controllerState.retry,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    if (controller == null) {
        LoadingIndicator()
        return
    }

    if (playerError != null) {
        // 错误层叠在画面上,需要压暗底图才能读清文案
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))) {
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
        }
        return
    }

    val hasOptions = playbackResult.pages.totalPages > 1 ||
        playbackResult.availableQualities.size > 1
    // OK 长按：部分 Android TV 遥控器的 MENU 键会被系统拦截去开通知/设置，
    // 长按 OK 是所有遥控器都有的可靠入口。短按抬起时切播放/暂停。
    var okHeld by remember { mutableStateOf(false) }
    var okLongConsumed by remember { mutableStateOf(false) }
    var okLongJob by remember { mutableStateOf<Job?>(null) }

    fun revealControls() {
        showControls = true
        lastInteractionMs = System.currentTimeMillis()
    }

    fun seekBy(deltaMs: Long) {
        val target = (controller.currentPosition + deltaMs).coerceAtLeast(0L)
        val duration = controller.duration
        controller.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    fun togglePlayback() {
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun switchEpisode(next: Boolean) {
        val now = System.currentTimeMillis()
        // 长按方向键会连发 KeyDown；700ms 内只切一次，避免连跳多集。
        if (now - lastEpisodeSwitchMs < EpisodeSwitchThrottleMs) return
        lastEpisodeSwitchMs = now
        if (next) onNext() else onPrevious()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(playerFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (down) {
                            seekBy(-SEEK_STEP_MS)
                            revealControls()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (down) {
                            seekBy(SEEK_STEP_MS)
                            revealControls()
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (down) {
                            switchEpisode(next = false)
                            revealControls()
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (down) {
                            switchEpisode(next = true)
                            revealControls()
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (down) {
                            revealControls()
                            if (!okHeld) {
                                okHeld = true
                                okLongConsumed = false
                                okLongJob?.cancel()
                                if (hasOptions) {
                                    okLongJob = scope.launch {
                                        delay(550)
                                        if (okHeld) {
                                            okLongConsumed = true
                                            showOptions = true
                                        }
                                    }
                                }
                            }
                        }
                        if (up) {
                            okHeld = false
                            okLongJob?.cancel()
                            okLongJob = null
                            if (!okLongConsumed) togglePlayback()
                            okLongConsumed = false
                            revealControls()
                        }
                        true
                    }
                    Key.MediaPlayPause -> {
                        if (down) {
                            togglePlayback()
                            revealControls()
                        }
                        true
                    }
                    Key.Menu -> {
                        if (down && hasOptions) {
                            showOptions = true
                            revealControls()
                        }
                        true
                    }
                    else -> false
                }
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
        // 触摸：左侧 30% 快退、右侧 30% 快进、中间播放/暂停。
        // 放在控制层下方，点顶部返回/选集胶囊时不会误触。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        revealControls()
                        val width = size.width.coerceAtLeast(1)
                        when {
                            offset.x < width * 0.3f -> seekBy(-SEEK_STEP_MS)
                            offset.x > width * 0.7f -> seekBy(SEEK_STEP_MS)
                            else -> togglePlayback()
                        }
                    }
                },
        )
        // 缓冲指示：画面未停但流没跟上时给出“正在加载”反馈
        if (isBuffering) {
            LoadingIndicator(modifier = Modifier.fillMaxSize())
        }
        // 中央播放状态：暂停时常驻 ▶，恢复播放时短暂显示 ❚❚ 后淡出。
        // 放在缓冲指示之后，暂停/缓冲时三角形不会被转圈盖住。
        PlaybackStateGlyph(
            isPlaying = isPlaying,
            modifier = Modifier.align(Alignment.Center),
        )
        // 控制条与解析失败错误层互斥渲染：错误层盖在控制条上时，
        // 焦点可能落在被遮挡的控件上（看不见却拿到焦点）。
        if (overlayError != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))) {
                ErrorView(
                    message = overlayError,
                    onRetry = onRetryResolve,
                    autoFocusRetry = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (showControls) {
            ControlsOverlay(
                videoTitle = videoTitle,
                playbackResult = playbackResult,
                collection = collection,
                positionMs = positionMs,
                durationMs = durationMs,
                onOptionsClick = { showOptions = true },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showOptions) {
            PlaybackOptionsDialog(
                playbackResult = playbackResult,
                onSelectQuality = onSelectQuality,
                onSelectPage = onSelectPage,
                onDismiss = { showOptions = false },
            )
        }
    }
}

@Composable
private fun PlaybackStateGlyph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(!isPlaying) }
    LaunchedEffect(isPlaying) {
        visible = true
        if (isPlaying) {
            delay(800)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(44.dp),
                ) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(13.dp)
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

private const val EpisodeSwitchThrottleMs = 700L
