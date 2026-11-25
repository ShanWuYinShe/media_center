package com.example.mediacenter.ui.player

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.core.content.edit
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import androidx.core.view.isNotEmpty
import com.google.android.exoplayer2.C
import com.example.mediacenter.net.AppHttpClient
import com.example.mediacenter.player.LegacyPlaybackMediaCache
import com.example.mediacenter.player.PlayerViewHolder
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.video.VideoSize
import com.example.mediacenter.MainActivity
import com.example.mediacenter.R
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.config.containsHeader
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchEntry
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.TvNavigationPolicy
import com.example.mediacenter.ui.setContent
import com.example.mediacenter.ui.showAllPagesDialog
import com.example.mediacenter.ui.showOptionListDialog
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.formatTime
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.linearWrap
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.config.PlaybackUserAgent
import com.example.mediacenter.ui.config.isRecoverablePlaybackError
import com.example.mediacenter.ui.config.playbackErrorMessage
import com.example.mediacenter.ui.config.playbackMimeType
import com.example.mediacenter.ui.config.playbackRequestHeaders
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.views.PlayPauseGlyphView

internal fun MainActivity.renderPlayer(state: MediaUiState) {
        val stream = state.playing ?: return
        // 恢复流程会先保持旧 stream 并显示 loading,延迟后再刷新播放地址。
        // 这段期间不要因播放器已进入 IDLE 而立刻重建同一个失败 URL。
        if (currentPlayerUrl == stream.url && root.isNotEmpty()) {
            if (state.loading || state.error != null || player?.playbackState != Player.STATE_IDLE) {
                // 切清晰度解析期间旧流仍在播:在真正换流前捕获当前位置(解析可能
                // 耗时 5-15s,点击时刻的位置会落后),新流 prepare 时直接 seek 过去。
                if (state.switchingQuality && pendingPlaybackSeekPosition < 0L) {
                    pendingPlaybackSeekPosition = player?.currentPosition ?: -1L
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                return
            }
        }

        val playbackUrl = stream.url
        val requestHeaders = playbackRequestHeaders(stream.headers)
        // 换视频时复位画面旋转并重新自动检测(签名 URL 自动恢复重建播放器时 playingVideo.id
        // 不变,故只在视频真正切换时复位,保留用户在同一视频内手动选择的旋转角度)。
        val playingVideoId = state.playingVideo?.id
        if (playingVideoId != null && playingVideoId != playerRotationVideoId) {
            playerRotationVideoId = playingVideoId
            playerVideoRotationDegrees = 0
            playerVideoRotationUserSet = false
        }
        val currentVideo = state.playingVideo ?: state.selected?.let { sel ->
            VideoSummary(
                id = sel.id,
                title = sel.title,
                thumbnailUrl = sel.thumbnailUrl,
                thumbnailUrlLarge = sel.thumbnailUrlLarge,
                durationSeconds = sel.durationSeconds,
            )
        }
        if (currentVideo != null) {
            val page = state.currentPages?.currentPage ?: 0
            val entry = WatchEntry(
                videoId = currentVideo.id,
                provider = state.currentProvider,
                title = currentVideo.title,
                thumbnailUrl = currentVideo.thumbnailUrl.orEmpty(),
                durationSeconds = currentVideo.durationSeconds ?: 0L,
                page = page,
            )
            WatchHistory.upsert(this, entry)
        }
        AppLog.i(
            "Player",
            "prepare stream=${stream.id} kind=${stream.kind} mime=${stream.mimeType} " +
                "url=${AppLog.redactUrl(playbackUrl)}",
        )
        // HttpsURLConnection 的 Conscrypt 默认值由后台初始化安装(见
        // MediaCenterApp);冷启动后立刻进播放页时这里等它就绪(通常≈0),
        // 避免 4.4.2 老盒子用平台 TLS 握手失败。
        AppHttpClient.awaitInit()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(requestHeaders)
        if (!stream.headers.containsHeader("User-Agent")) {
            dataSourceFactory.setUserAgent(PlaybackUserAgent)
        }

        // 起播优先:取消仍在进行的首段预取,避免与播放器抢带宽
        // (已落盘的缓存 span 保留并继续被播放命中)。
        LegacyPlaybackMediaCache.cancelPrefetches()
        // 先释放旧播放器，此时 currentPlayerOptionId 仍是旧值，
        // releasePlayer 会用旧 id 正确保存当前进度。
        releasePlayer()
        // 更新为当前流的 id，后续加载和保存进度都使用此键。
        currentPlayerOptionId = stream.id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 在 prepare 之前加载上次保存的位置并一次 seek 到位，避免在 STATE_READY
        // 回调中 seek 导致 seek→buffer→ready→seek 的死循环。
        // 切清晰度/错误重试重建时优先用重建前捕获的精确位置(见 openPlayerOptionsDialog):
        // 新旧流 option id 不同,按持久化 key 查不到旧进度。
        val initialSeekPos = when {
            currentState.loading -> 0L
            pendingPlaybackSeekPosition >= 0L -> {
                val pending = pendingPlaybackSeekPosition
                pendingPlaybackSeekPosition = -1L
                pending
            }
            else -> loadPlaybackPosition()
        }
        var builtPlayer: ExoPlayer? = null
        try {
            builtPlayer = ExoPlayer.Builder(this)
                // 起播缓冲调优:默认 bufferForPlayback=2.5s,老盒子解析完还要再等缓冲;
                // 降到 1.2s 起播、重缓冲 3s(min/max 仍 15s/50s),优先保证"点了就出画"。
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15_000, 50_000, 1_200, 3_000)
                        .build(),
                )
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        // 播放数据源套媒体磁盘缓存:预取的首段命中读磁盘,首播
                        // write-through 边播边写,重看/续播直接命中(见
                        // LegacyPlaybackMediaCache);缓存异常自动回退 dataSourceFactory。
                        LegacyPlaybackMediaCache.cacheDataSourceFactory(this, dataSourceFactory),
                    )
                        .setLoadErrorHandlingPolicy(playbackLoadErrorHandlingPolicy),
                )
                // 交给 ExoPlayer 管理音频焦点:被系统提示音/其他应用抢占焦点时自动暂停或压低,
                // 焦点恢复后续播,避免长时间播放被抢焦点后"没声音";同时在耳机/音频路由断开
                // (becoming noisy)时暂停,符合 TV/移动端音频惯例。
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build()
            builtPlayer.apply {
                val seekPos = initialSeekPos.coerceAtLeast(0L)
                val mediaItem = MediaItem.Builder()
                    .setUri(playbackUrl)
                    .setMimeType(playbackMimeType(stream.mimeType))
                    .build()
                setMediaItem(mediaItem, seekPos)
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            // 就绪后才开始计稳定计时;持续顺畅播放超过窗口才清零恢复额度,
                            // 避免“就绪即失败”的流靠 STATE_READY 反复复位计数器造成无限恢复循环。
                            // 续播位置已在 prepare 之前通过 setMediaItem(..., seekPos) 或
                            // seekTo() 一次设好，不在回调中 seek 以避免 seek→buffer→ready 死循环。
                            Player.STATE_READY -> {
                                AppLog.i("Player", "stream ${stream.id} READY")
                                schedulePlaybackStabilityReset(stream.id)
                            }
                            Player.STATE_ENDED -> {
                                AppLog.i("Player", "stream ${stream.id} ENDED -> playNext")
                                cancelPlaybackStabilityReset()
                                // 播放完成，清除保存的位置。
                                val key = playbackPosKey()
                                if (key.isNotEmpty()) prefs().edit { remove(key) }
                                viewModel.playNext()
                            }
                            Player.STATE_BUFFERING -> {
                                AppLog.d("Player", "stream ${stream.id} BUFFERING")
                                cancelPlaybackStabilityReset()
                            }
                            else -> cancelPlaybackStabilityReset()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val recoverable = isRecoverablePlaybackError(error)
                        AppLog.w(
                            "Player",
                            "stream ${stream.id} error code=${error.errorCode} " +
                                "name=${error.errorCodeName} recoverable=$recoverable",
                            error,
                        )
                        cancelPlaybackStabilityReset()
                        viewModel.playbackFailed(
                            playbackErrorMessage(error),
                            recoverable = recoverable,
                        )
                    }
                })
                prepare()
            }
            player = builtPlayer
            currentPlayerUrl = playbackUrl
        } catch (e: Exception) {
            AppLog.e("Player", "Failed to create/prepare ExoPlayer for stream ${stream.id}", e)
            builtPlayer?.release()
            viewModel.playbackFailed("播放器初始化失败，请重试", recoverable = false)
            return
        }
        val nextPlayer = player!!

        val overlay = playerOverlay(stream, nextPlayer, state)
        val hideOverlay = Runnable {
            overlay.animate().alpha(0f).setDuration(400).start()
        }
        fun shouldAutoHideOverlay(): Boolean {
            // 只有真正 READY 播放时才自动收起；缓冲/切集时保留信息层，
            // 否则老人只看到黑屏转圈，不知道当前在做什么。
            return nextPlayer.playWhenReady && nextPlayer.playbackState == Player.STATE_READY
        }
        fun updateOverlayAutoHide() {
            overlay.removeCallbacks(hideOverlay)
            if (shouldAutoHideOverlay()) {
                overlay.postDelayed(hideOverlay, 4_000)
            }
        }
        fun syncOverlayVisibilityForPlaybackState() {
            overlay.removeCallbacks(hideOverlay)
            if (shouldAutoHideOverlay()) {
                if (overlay.alpha > 0f) overlay.postDelayed(hideOverlay, 4_000)
            } else {
                overlay.animate().cancel()
                overlay.alpha = 1f
            }
        }
        fun showOverlay() {
            overlay.animate().cancel()
            overlay.alpha = 1f
            updateOverlayAutoHide()
        }
        fun controlsVisible(): Boolean = overlay.alpha > 0.5f
        fun playFromCurrentPosition() {
            if (nextPlayer.playbackState == Player.STATE_ENDED) {
                nextPlayer.seekTo(0)
            }
            nextPlayer.play()
            showOverlay()
        }
        fun pausePlayback() {
            nextPlayer.pause()
            showOverlay()
        }
        fun togglePlayback() {
            if (nextPlayer.playbackState == Player.STATE_ENDED) {
                playFromCurrentPosition()
            } else if (nextPlayer.playWhenReady) {
                pausePlayback()
            } else {
                playFromCurrentPosition()
            }
        }

        // 快进快退 HUD 悬浮指示
        val seekGlyph = com.example.mediacenter.ui.views.SeekDirectionGlyphView(this)
        val seekDeltaLabel = label("+10秒", 24f, Color.WHITE, bold = true, maxLines = 1)
        val seekTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seekGlyph, LinearLayout.LayoutParams(dp(24), dp(18)))
            addView(seekDeltaLabel, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(10)))
        }
        val seekTargetLabel = label("0:00 / 0:00", 15f, Colors.TEXT_SECONDARY, bold = false, maxLines = 1)
        val seekHudContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = rounded(Color.argb(192, 0, 0, 0), 12)
            addView(seekTopRow, LinearLayout.LayoutParams(wrap(), wrap()))
            addView(seekTargetLabel, LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(6)))
        }
        val seekHudWrap = FrameLayout(this).apply {
            alpha = 0f
            visibility = View.GONE
            isFocusable = false
            addView(seekHudContent, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }

        var lastSeekTimeMs = 0L
        var lastSeekDirection = 0
        var consecutiveSeekCount = 0
        var seekHudDeltaMs = 0L
        var seekHudTargetMs = 0L
        val hideSeekHud = Runnable {
            seekHudWrap.animate().alpha(0f).setDuration(250).withEndAction {
                seekHudWrap.visibility = View.GONE
                consecutiveSeekCount = 0
                seekHudDeltaMs = 0L
                seekHudTargetMs = 0L
            }.start()
        }

        hidePlayerControlsAction = {
            val hudShowing = seekHudWrap.visibility == View.VISIBLE || seekHudWrap.alpha > 0f
            if (controlsVisible() || hudShowing) {
                overlay.removeCallbacks(hideOverlay)
                overlay.animate().cancel()
                overlay.animate().alpha(0f).setDuration(250).start()
                seekHudWrap.removeCallbacks(hideSeekHud)
                seekHudWrap.animate().cancel()
                seekHudWrap.visibility = View.GONE
                seekHudWrap.alpha = 0f
                consecutiveSeekCount = 0
                seekHudDeltaMs = 0L
                seekHudTargetMs = 0L
                true
            } else {
                false
            }
        }

        fun performSeek(forward: Boolean) {
            val now = System.currentTimeMillis()
            val currentPos = nextPlayer.currentPosition.coerceAtLeast(0L)
            val duration = nextPlayer.duration.coerceAtLeast(0L)
            val result = SeekStepCalculator.calculateStep(
                forward = forward,
                nowMs = now,
                currentPosMs = currentPos,
                durationMs = duration,
                lastSeekTimeMs = lastSeekTimeMs,
                lastSeekDirection = lastSeekDirection,
                prevConsecutiveCount = consecutiveSeekCount,
                prevAccumulatedDeltaMs = seekHudDeltaMs,
                prevTargetPositionMs = seekHudTargetMs,
            )
            lastSeekTimeMs = now
            lastSeekDirection = result.direction
            consecutiveSeekCount = result.consecutiveCount
            seekHudDeltaMs = result.accumulatedDeltaMs
            seekHudTargetMs = result.targetPositionMs

            nextPlayer.seekTo(result.targetPositionMs)
            showOverlay()

            seekGlyph.setForward(forward)
            seekDeltaLabel.text = SeekStepCalculator.formatDelta(result.accumulatedDeltaMs)
            seekTargetLabel.text = SeekStepCalculator.formatTarget(result.targetPositionMs, duration)

            seekHudWrap.removeCallbacks(hideSeekHud)
            seekHudWrap.animate().cancel()
            seekHudWrap.visibility = View.VISIBLE
            seekHudWrap.alpha = 1f
            seekHudWrap.postDelayed(hideSeekHud, SeekStepCalculator.SEEK_HUD_TIMEOUT_MS)
        }

        // OK 键:短按播放/暂停,长按(约 0.5 秒)打开播放设置 —— 方向键已被
        // 快退/快进、上下集占满；MENU 键在部分 Android TV 上会被系统拦截去开
        // 通知/设置，长按 OK 是所有遥控器都具备的可靠入口。
        val longPressMs = 450L
        var centerDownTime = 0L
        var centerConsumedByLong = false
        fun isCenterKey(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                // 只记首次按下(ACTION_DOWN + repeatCount=0):长按会以几十 Hz 重复
                // 派发 ACTION_DOWN,全记会在日志里刷屏,淹没真正的现场。
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    AppLog.i("PlayerKey", "code=$keyCode action=${event.action} repeat=${event.repeatCount} long=${event.isLongPress} dt=${event.eventTime - centerDownTime}")
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    if (!isCenterKey(keyCode)) return@setOnKeyListener false
                    val handledByLong = centerConsumedByLong
                    centerConsumedByLong = false
                    if (!handledByLong) {
                        if (event.eventTime - centerDownTime >= longPressMs) {
                            openPlayerOptionsDialog()
                            showOverlay()
                        } else {
                            togglePlayback()
                        }
                    }
                    return@setOnKeyListener true
                }
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // 控制层隐藏时方向键也直接生效：先亮出信息层，再继续走下面的
                // seek/切集分支，不需要“第一下只唤出、第二下才操作”。
                if (!controlsVisible()) showOverlay()
                if (isCenterKey(keyCode)) {
                    if (event.repeatCount == 0) {
                        centerDownTime = event.eventTime
                        centerConsumedByLong = false
                        event.startTracking()
                    } else if (event.isLongPress && !centerConsumedByLong) {
                        openPlayerOptionsDialog()
                        centerConsumedByLong = true
                        showOverlay()
                    }
                    return@setOnKeyListener true
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> {
                        if (event.repeatCount == 0) togglePlayback()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (event.repeatCount == 0) playFromCurrentPosition()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (event.repeatCount == 0) pausePlayback()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        performSeek(forward = false)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        performSeek(forward = true)
                        true
                    }
                    // MEDIA_PREVIOUS/NEXT:专用键,直通。
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        viewModel.playPrevious()
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        viewModel.playNext()
                        showOverlay()
                        true
                    }
                    // 上下键直接切上一/下一集；长按连发不连续切，避免误按跳多集。
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.repeatCount == 0) {
                            viewModel.playPrevious()
                            showOverlay()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) {
                            viewModel.playNext()
                            showOverlay()
                        }
                        true
                    }
                    // MENU/INFO 仅作兼容兜底；部分 TV 会把它拦截成系统通知/设置。
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        if (event.repeatCount == 0) {
                            openPlayerOptionsDialog()
                            showOverlay()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        if (event.repeatCount == 0) {
                            val errorShowing = playerErrorOverlay?.visibility == View.VISIBLE ||
                                playerRetryOverlay?.visibility == View.VISIBLE
                            val decision = TvNavigationPolicy.evaluatePlayerBack(
                                controlsVisible = controlsVisible(),
                                isErrorShowing = errorShowing,
                            )
                            if (decision == TvNavigationPolicy.PlayerBackDecision.HIDE_CONTROLS_OVERLAY) {
                                hidePlayerControlsAction?.invoke()
                            } else {
                                hidePlayerControlsAction = null
                                viewModel.back()
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.repeatCount == 0) {
                            val errorShowing = playerErrorOverlay?.visibility == View.VISIBLE ||
                                playerRetryOverlay?.visibility == View.VISIBLE
                            val decision = TvNavigationPolicy.evaluatePlayerBack(
                                controlsVisible = controlsVisible(),
                                isErrorShowing = errorShowing,
                            )
                            if (decision == TvNavigationPolicy.PlayerBackDecision.HIDE_CONTROLS_OVERLAY) {
                                if (hidePlayerControlsAction?.invoke() == true) {
                                    return@setOnKeyListener true
                                }
                            }
                        }
                        false
                    }
                    else -> false
                }
            }
        }
        val holder = PlayerViewHolder.inflate(layoutInflater, screen, false)
        val playerView = holder.playerView.apply {
            player = nextPlayer
            useController = false
            setBackgroundColor(Color.BLACK)
            // FIT 会按视频真实宽高比自动 letterbox/pillarbox;旋转时外层会交换视图宽高。
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerSurface = holder
        screen.addView(playerView, FrameLayout.LayoutParams(match(), match(), Gravity.CENTER))
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPlayerVideoRotation() }
        // 缓冲指示:独立于自动隐藏的控制层,只要播放器处于 BUFFERING 就常驻显示,
        // 避免控制层淡出后用户以为卡死。
        val bufferingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            // TalkBack:纯图形进度件需要可朗读的状态说明
            contentDescription = "缓冲中"
            // indeterminateTintList 是 API 21+,API 19 上调用会 NoSuchMethodError。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = ColorStateList.valueOf(Colors.APPLE_BLUE)
            }
            visibility = if (nextPlayer.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
        // 缓冲转圈上移 72dp:中央 96dp 播放/暂停圆盘与它同心叠加,且圆盘后添加、
        // 画在上层,缓冲前 ~1s 会把转圈整个盖住(看起来像没有缓冲提示)。
        screen.addView(
            bufferingSpinner,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER).apply { bottomMargin = dp(72) },
        )
        // 中央播放状态：暂停时常驻 ▶，播放时短暂显示 ❚❚ 后淡出。
        val centerGlyph = PlayPauseGlyphView(this)
        val centerGlyphWrap = FrameLayout(this).apply {
            background = rounded(Color.argb(140, 0, 0, 0), 999)
            alpha = 0f
            isFocusable = false
            addView(centerGlyph, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        screen.addView(centerGlyphWrap, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        screen.addView(seekHudWrap, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        fun updateCenterGlyph() {
            val playing = nextPlayer.playWhenReady &&
                nextPlayer.playbackState != Player.STATE_ENDED &&
                nextPlayer.playbackState != Player.STATE_IDLE
            centerGlyph.setPlaying(playing)
            centerGlyphWrap.animate().cancel()
            centerGlyphWrap.visibility = View.VISIBLE
            if (playing) {
                centerGlyphWrap.alpha = 1f
                centerGlyphWrap.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
            } else {
                centerGlyphWrap.alpha = 1f
            }
        }
        screen.addView(overlay, frameMatch())
        nextPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncOverlayVisibilityForPlaybackState()
                updateCenterGlyph()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingSpinner.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                syncOverlayVisibilityForPlaybackState()
                updateCenterGlyph()
            }

            // 拿到真实分辨率后自动判定是否需要把画面旋转到 TV 横屏上;
            // 一旦用户手动选择过旋转角度,则不再自动覆盖。
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    autoDetectPlayerVideoRotation(videoSize)
                }
            }
        })
        applyPlayerVideoRotation()
        updateCenterGlyph()
        // 触摸：整屏点按播放/暂停；进度条是只读指示器，不参与焦点。
        screen.setOnClickListener { togglePlayback() }
        playerScreen = screen
        setContent(screen)
        screen.requestFocus()
        showOverlay()
    }

internal fun MainActivity.playerOverlay(stream: PlaybackOption, player: ExoPlayer, state: MediaUiState): FrameLayout {
        val videoTitle = state.playingVideo?.title?.takeIf { it.isNotBlank() } ?: "正在播放"
        val overlay = FrameLayout(this)

        // ---------------------- 顶部:返回 + 标题 + 集数/清晰度 ----------------------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(56), dp(36), dp(56), dp(72))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(204, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 触摸/鼠标可点的返回；电视端不参与焦点遍历，BACK 键仍然是主路径。
        val backButton = label("返回", 17f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = false
            isClickable = true
            setOnClickListener { viewModel.back() }
        }
        titleRow.addView(backButton, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(18)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                label(videoTitle, 30f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
                    letterSpacingCompat(-0.01f)
                },
                linearMatch(),
            )
            val meta = buildList {
                state.currentPages?.let { pages ->
                    if (pages.totalPages > 1) {
                        add("第 ${pages.currentPage + 1} / ${pages.totalPages} 集")
                    }
                }
                stream.label.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                addView(
                    label(meta, 20f, Colors.TEXT_SECONDARY, maxLines = 1),
                    LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(6)),
                )
            }
        }
        titleRow.addView(titleColumn, LinearLayout.LayoutParams(0, wrap(), 1f))
        // 触摸入口：长按 OK 是电视端路径，这个胶囊给鼠标/触摸直接打开同一弹窗。
        val pages = state.currentPages
        val hasEpisodes = pages != null && pages.totalPages > 1
        val qualityLabel = stream.label.takeIf { it.isNotBlank() } ?: "默认"
        val optionsLabel = if (hasEpisodes) "选集 · $qualityLabel" else "清晰度 · $qualityLabel"
        val optionsButton = label(optionsLabel, 17f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = false
            isClickable = true
            setOnClickListener { openPlayerOptionsDialog() }
        }
        titleRow.addView(
            optionsButton,
            LinearLayout.LayoutParams(wrap(), dp(48)).withStartMargin(dp(18)),
        )
        top.addView(titleRow, LinearLayout.LayoutParams(match(), wrap()))
        overlay.addView(top, FrameLayout.LayoutParams(match(), wrap(), Gravity.TOP))

        // ---------------------- 底部:玻璃传输栏 ----------------------
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(56), dp(96), dp(56), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(214, 0, 0, 0)),
            )
        }

        // 播放/暂停、快退/快进、上/下集全部由遥控器按键直接驱动，不在画面上
        // 放可点击/可聚焦的控制按钮——那是触屏播放器的交互。这里只保留进度与
        // 大号按键说明；播放状态由屏幕中央的三角形/两条竖杠表达。
        // 进度条:缓冲段 + 已播段 + 拖动点。只读，不参与焦点。
        val bufferedFill = View(this).apply {
            background = rounded(Color.argb(64, 255, 255, 255), 999)
            pivotX = 0f
            scaleX = 0f
        }
        val progressFill = View(this).apply {
            background = rounded(Colors.APPLE_BLUE, 999)
            pivotX = 0f
            scaleX = 0f
        }
        val progressKnob = View(this).apply {
            background = rounded(Color.WHITE, 999)
            elevationCompat(dp(2).toFloat())
        }
        val progressTrack = FrameLayout(this).apply {
            background = rounded(Color.argb(38, 255, 255, 255), 999)
            clipChildren = false
            addView(bufferedFill, FrameLayout.LayoutParams(match(), dp(8), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(progressFill, FrameLayout.LayoutParams(match(), dp(8), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(progressKnob, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        val timeCurrent = label("0:00", 18f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1)
        val timeTotal = label("0:00", 18f, Colors.TEXT_SECONDARY, maxLines = 1)
        val scrubberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(timeCurrent, LinearLayout.LayoutParams(wrap(), wrap()).withEndMargin(dp(16)))
            addView(progressTrack, LinearLayout.LayoutParams(0, dp(18), 1f))
            addView(timeTotal, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(16)))
        }
        bottom.addView(scrubberRow, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(24)))

        val hasPages = pages != null && pages.totalPages > 1
        val hasPrevious = if (hasPages) (pages?.currentPage ?: 0) > 0 else false
        val hasNext = if (hasPages) (pages?.currentPage ?: 0) < (pages?.totalPages ?: 0) - 1 else false
        val hintParts = mutableListOf("← 快退10秒", "→ 快进10秒")
        if (hasPrevious) hintParts.add("↑ 上一集")
        if (hasNext) hintParts.add("↓ 下一集")
        hintParts.add("OK 播放/暂停")
        val keyHints = label(
            hintParts.joinToString("    "),
            17f,
            Colors.TEXT_PRIMARY,
            maxLines = 1,
        ).apply { gravity = Gravity.CENTER }
        bottom.addView(keyHints, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(18)))
        val secondaryHint = label(
            buildString {
                append("长按 ← → 连续快退/快进")
                append(" · MENU/长按 OK 选集/清晰度")
            },
            14f,
            Colors.TEXT_SECONDARY,
            maxLines = 1,
        ).apply { gravity = Gravity.CENTER }
        bottom.addView(secondaryHint, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(6)))

        overlay.addView(bottom, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

        fun updateProgress() {
            if (player.playbackState == Player.STATE_IDLE && !player.playWhenReady) return
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val position = player.currentPosition.coerceAtLeast(0L)
            val buffered = player.bufferedPosition.coerceAtLeast(position)
            val trackWidth = progressTrack.width
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val filledWidth = (trackWidth * fraction).toInt()
            bufferedFill.scaleX = bufferedFraction
            progressFill.scaleX = fraction
            // knob 宽 16dp:上限必须按 16dp 算,否则进度拉满时右缘越出轨道 2dp
            // (当前靠 progressTrack.clipChildren=false 兜住,不应依赖这一点)。
            val maxKnobX = (trackWidth - dp(16)).coerceAtLeast(0).toFloat()
            progressKnob.translationX = (filledWidth - dp(16) / 2f).coerceIn(0f, maxKnobX)
            timeCurrent.setTextIfChanged(formatTime(position))
            timeTotal.setTextIfChanged(formatTime(duration))
        }
        val progressTick = object : Runnable {
            override fun run() {
                if (overlay.parent == null) return
                // overlay 淡出(alpha=0,隐藏)后不再刷新进度控件:状态变化已由
                // Player.Listener(onPlaybackStateChanged 等)驱动,这里只负责
                // 进度条平滑推进。避免低端盒子整个播放过程每 300ms 空转改 View。
                if (overlay.alpha > 0.5f) {
                    updateProgress()
                }
                overlay.postDelayed(this, 300)
            }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgress()
            }
        }
        player.addListener(listener)
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                updateProgress()
                overlay.post(progressTick)
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (player.playbackState != Player.STATE_IDLE || player.playWhenReady) {
                    player.removeListener(listener)
                }
                overlay.removeCallbacks(progressTick)
            }
        })
        return overlay
    }


/** 选集与播放设置：选集（多P） + 清晰度 + 旋转画面。长按 OK / MENU 或触摸胶囊共用。 */
internal fun MainActivity.openPlayerOptionsDialog() {
    val state = viewModel.state.value
    val pages = state.currentPages
    val hasEpisodes = pages != null && pages.totalPages > 1
    val qualities = state.availableQualities
    val currentQn = state.playing?.qn ?: 0

    val labels = mutableListOf<String>()
    var episodeIndex = -1
    if (hasEpisodes) {
        episodeIndex = labels.size
        labels.add("选集（当前第 ${pages.currentPage + 1} / ${pages.totalPages} 集）")
    }
    val qualityStartIndex = labels.size
    qualities.forEach { labels.add(it.label) }
    val rotateIndex = labels.size
    labels.add("旋转画面")

    val currentQualityOffset = qualities.indexOfFirst { it.qn == currentQn }.takeIf { it >= 0 }
    val defaultHighlightIndex = if (currentQualityOffset != null) qualityStartIndex + currentQualityOffset else null

    showOptionListDialog(
        title = if (hasEpisodes) "选集与播放设置" else "播放设置",
        options = labels,
        currentIndex = defaultHighlightIndex,
    ) { index ->
        when {
            index == episodeIndex -> {
                pages?.let { showAllPagesDialog(it) }
            }
            index in qualityStartIndex until rotateIndex -> {
                viewModel.selectPlaybackQuality(qualities[index - qualityStartIndex].qn)
            }
            index == rotateIndex -> {
                togglePlayerOrientation()
            }
        }
    }
}
