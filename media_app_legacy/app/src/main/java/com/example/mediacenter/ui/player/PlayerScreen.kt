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
import com.example.mediacenter.player.PlayerViewHolder
import com.google.android.exoplayer2.audio.AudioAttributes
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
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.setContent
import com.example.mediacenter.ui.showOptionListDialog
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.formatTime
import com.example.mediacenter.ui.components.dp
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
import com.example.mediacenter.ui.views.TransportIcon
import com.example.mediacenter.ui.views.TransportIconView

internal fun MainActivity.renderPlayer(state: MediaUiState) {
        val stream = state.playing ?: return
        // 恢复流程会先保持旧 stream 并显示 loading,延迟后再刷新播放地址。
        // 这段期间不要因播放器已进入 IDLE 而立刻重建同一个失败 URL。
        if (currentPlayerUrl == stream.url && root.isNotEmpty()) {
            if (state.loading || state.error != null || player?.playbackState != Player.STATE_IDLE) {
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
        AppLog.i(
            "Player",
            "prepare stream=${stream.id} kind=${stream.kind} mime=${stream.mimeType} " +
                "url=${AppLog.redactUrl(playbackUrl)}",
        )
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(requestHeaders)
        if (!stream.headers.containsHeader("User-Agent")) {
            dataSourceFactory.setUserAgent(PlaybackUserAgent)
        }

        // 先释放旧播放器，此时 currentPlayerOptionId 仍是旧值，
        // releasePlayer 会用旧 id 正确保存当前进度。
        releasePlayer()
        // 更新为当前流的 id，后续加载和保存进度都使用此键。
        currentPlayerOptionId = stream.id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 在 prepare 之前加载上次保存的位置并一次 seek 到位，避免在 STATE_READY
        // 回调中 seek 导致 seek→buffer→ready→seek 的死循环。
        val initialSeekPos = if (!currentState.loading) loadPlaybackPosition() else 0L
        var builtPlayer: ExoPlayer? = null
        try {
            builtPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(dataSourceFactory)
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
            return nextPlayer.playWhenReady && nextPlayer.playbackState != Player.STATE_ENDED
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
        fun isDirectionalDpad(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        }
        fun isPrimaryDpadAction(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        }
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

        // OK 键:短按播放/暂停,长按(按住约 0.5 秒)旋转画面 —— 遥控器方向键已被
        // 快退/快进、上一个/下一个占满,且 Android TV 不把 MENU 键派发给应用,长按 OK 是
        // 所有遥控器都具备、可靠的“直接操作”入口。短按动作放到抬起时判定,以便区分长按。
        val longPressMs = 450L
        var centerDownTime = 0L
        var centerConsumedByLong = false
        var centerRevealOnly = false
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
                    val revealOnly = centerRevealOnly
                    centerConsumedByLong = false
                    centerRevealOnly = false
                    if (!handledByLong && !revealOnly) {
                        if (event.eventTime - centerDownTime >= longPressMs) {
                            togglePlayerOrientation()
                            showOverlay()
                        } else {
                            togglePlayback()
                        }
                    }
                    return@setOnKeyListener true
                }
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if ((isDirectionalDpad(keyCode) || isPrimaryDpadAction(keyCode)) && !controlsVisible()) {
                    showOverlay()
                    if (isCenterKey(keyCode) && event.repeatCount == 0) {
                        centerDownTime = event.eventTime
                        centerConsumedByLong = false
                        centerRevealOnly = true
                    }
                    return@setOnKeyListener true
                }
                if (isCenterKey(keyCode)) {
                    if (event.repeatCount == 0) {
                        centerDownTime = event.eventTime
                        centerConsumedByLong = false
                        centerRevealOnly = false
                        event.startTracking()
                    } else if (event.isLongPress && !centerConsumedByLong) {
                        togglePlayerOrientation()
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
                        nextPlayer.seekBack()
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        nextPlayer.seekForward()
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        viewModel.playPrevious()
                        showOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        viewModel.playNext()
                        showOverlay()
                        true
                    }
                    // MENU/INFO 键:多清晰度视频弹清晰度选择;只有一种清晰度时
                    // 保持原行为(旋转画面),清晰度弹窗没有单选项的价值。
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        if (event.repeatCount == 0) {
                            val qualities = viewModel.state.value.availableQualities
                            if (qualities.size > 1) {
                                val currentQn = viewModel.state.value.playing?.qn ?: 0
                                val currentIndex = qualities.indexOfFirst { it.qn == currentQn }
                                    .takeIf { it >= 0 }
                                showOptionListDialog(
                                    title = "清晰度",
                                    options = qualities.map { it.label },
                                    currentIndex = currentIndex,
                                ) { index ->
                                    qualities.getOrNull(index)?.let { viewModel.selectPlaybackQuality(it.qn) }
                                }
                            } else {
                                togglePlayerOrientation()
                                showOverlay()
                            }
                        }
                        true
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
            // indeterminateTintList 是 API 21+,API 19 上调用会 NoSuchMethodError。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = ColorStateList.valueOf(Colors.APPLE_BLUE)
            }
            visibility = if (nextPlayer.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
        screen.addView(bufferingSpinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        screen.addView(overlay, frameMatch())
        nextPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncOverlayVisibilityForPlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingSpinner.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                syncOverlayVisibilityForPlaybackState()
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
        setContent(screen)
        screen.requestFocus()
        showOverlay()
    }

internal fun MainActivity.playerOverlay(stream: PlaybackOption, player: ExoPlayer, state: MediaUiState): FrameLayout {
        val playingIndex = state.playingVideo
            ?.let { current -> state.items.indexOfFirst { it.id == current.id } }
            ?: -1
        val previousTitle = if (playingIndex > 0) state.items[playingIndex - 1].title else null
        val nextTitle = if (playingIndex >= 0 && playingIndex + 1 < state.items.size) state.items[playingIndex + 1].title else null
        val hasPrevious = previousTitle != null
        val hasNext = nextTitle != null || state.canLoadMore
        val videoTitle = state.playingVideo?.title?.takeIf { it.isNotBlank() } ?: "正在播放"
        val overlay = FrameLayout(this)

        // ---------------------- 顶部:标题 + 状态 ----------------------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(56), dp(36), dp(56), dp(72))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(204, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        top.addView(
            label(videoTitle, 26f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2).apply {
                letterSpacingCompat(-0.01f)
            },
            linearMatch(),
        )
        state.currentPages?.let { pages ->
            if (pages.totalPages > 1) {
                val pageTitle = pages.pageTitles.getOrNull(pages.currentPage)?.takeIf { it.isNotBlank() }
                val pageLabel = pageTitle ?: "第${pages.currentPage + 1}集"
                top.addView(
                    label("$pageLabel · ${pages.currentPage + 1}/${pages.totalPages}", 16f, Colors.TEXT_SECONDARY, maxLines = 1),
                    LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(8)),
                )
            }
        }
        val statusDot = View(this).apply { background = rounded(Colors.APPLE_BLUE, 999) }
        val statusText = label("", 15f, Colors.TEXT_SECONDARY, bold = true, maxLines = 1)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).withEndMargin(dp(10)))
            addView(statusText, linearWrap())
            // 多清晰度时提示 MENU 键可切换(新交互的发现性提示)。
            if (state.availableQualities.size > 1) {
                addView(
                    label("MENU 键切换清晰度", 13f, Colors.TEXT_TERTIARY, maxLines = 1),
                    LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(16)),
                )
            }
        }
        top.addView(statusRow, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(14)))
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

        // 播放/暂停、快退/快进、上/下集全部由遥控器按键直接驱动(见底部按键提示),不在画面上
        // 放可点击/可聚焦的控制按钮——那是触屏播放器的交互。这里只保留状态(顶部“正在播放/已暂停”)、
        // 进度条与画面比例指示等只读信息。`transportIcon` 仅用于画面比例指示的小图标。
        fun transportIcon(icon: TransportIcon, enabled: Boolean): TransportIconView =
            TransportIconView(this, icon).apply {
                setIconColor(Colors.TEXT_PRIMARY)
                alpha = if (enabled) 1f else 0.3f
                isFocusable = false
                isClickable = false
            }

        // 画面旋转只读指示:由长按 OK/MENU 键直接切换,这里展示当前角度,非交互。
        val orientationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                transportIcon(TransportIcon.Aspect, true),
                LinearLayout.LayoutParams(dp(18), dp(18)).withEndMargin(dp(8)),
            )
            addView(
                label("画面 · ${videoRotationLabel()}", 13f, Colors.TEXT_TERTIARY, maxLines = 1)
                    .apply { gravity = Gravity.CENTER }
                    .also { orientationIndicator = it },
                linearWrap(),
            )
        }
        bottom.addView(
            orientationRow,
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(12)),
        )

        // 进度条:缓冲段 + 已播段 + 拖动点。
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
            addView(bufferedFill, FrameLayout.LayoutParams(match(), dp(6), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(progressFill, FrameLayout.LayoutParams(match(), dp(6), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(progressKnob, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        val timeCurrent = label("0:00", 14f, Colors.TEXT_SECONDARY, bold = true, maxLines = 1)
        val timeTotal = label("0:00", 14f, Colors.TEXT_TERTIARY, maxLines = 1)
        val scrubberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(timeCurrent, LinearLayout.LayoutParams(wrap(), wrap()).withEndMargin(dp(16)))
            addView(progressTrack, LinearLayout.LayoutParams(0, dp(14), 1f))
            addView(timeTotal, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(16)))
        }
        bottom.addView(scrubberRow, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(24)))

        val hintBase = "方向键唤出控制层 · OK 播放/暂停 · ← → 快退/快进 10 秒 · 长按 OK 旋转画面"
        val hintText = buildString {
            append(hintBase)
            if (hasPrevious) append(" · ↑ 上一个")
            if (hasNext) append(" · ↓ 下一个")
        }
        val hint = label(
            hintText,
            13f,
            Colors.TEXT_TERTIARY,
            maxLines = 1,
        ).apply { gravity = Gravity.CENTER }
        bottom.addView(hint, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(16)))

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
            val maxKnobX = (trackWidth - dp(14)).coerceAtLeast(0).toFloat()
            progressKnob.translationX = (filledWidth - dp(14) / 2f).coerceIn(0f, maxKnobX)
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
        fun updatePlaybackGlyph() {
            val isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            statusText.setTextIfChanged(when {
                player.playbackState == Player.STATE_ENDED -> "已结束 · ${stream.label.ifBlank { "默认" }}"
                player.playbackState == Player.STATE_BUFFERING -> "缓冲中 · ${stream.label.ifBlank { "默认" }}"
                player.playWhenReady -> "正在播放 · ${stream.label.ifBlank { "默认" }}"
                else -> "已暂停 · ${stream.label.ifBlank { "默认" }}"
            })
            val dotColor = when {
                player.playbackState == Player.STATE_BUFFERING -> Colors.TEXT_TERTIARY
                isPlaying -> Colors.APPLE_BLUE
                else -> Colors.TEXT_SECONDARY
            }
            statusDot.background = rounded(dotColor, 999)
        }
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackGlyph()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgress()
                updatePlaybackGlyph()
            }
        }
        player.addListener(listener)
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                updateProgress()
                updatePlaybackGlyph()
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

