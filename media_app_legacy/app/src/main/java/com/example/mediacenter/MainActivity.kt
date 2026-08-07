package com.example.mediacenter

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isNotEmpty
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.MimeTypes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.isTransientHttpStatusCode
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.MediaViewModel
import com.example.mediacenter.ui.MediaViewModelFactory
import com.example.mediacenter.ui.ImageMemoryCache
import com.example.mediacenter.ui.ThumbnailFetchSemaphore
import com.example.mediacenter.ui.ThumbnailHttpClient
import com.example.mediacenter.ui.ThumbnailHttpException
import com.example.mediacenter.ui.config.CategorySelectDebounceMs
import com.example.mediacenter.ui.config.DIAGNOSTIC_LEFT_PRESS_COUNT
import com.example.mediacenter.ui.config.FooterStableId
import com.example.mediacenter.ui.config.MaxPlaybackRetries
import com.example.mediacenter.ui.config.MaxThumbnailBytes
import com.example.mediacenter.ui.config.MaxThumbnailRetries
import com.example.mediacenter.ui.config.PlaybackStabilityResetMs
import com.example.mediacenter.ui.config.PlaybackUserAgent
import com.example.mediacenter.ui.config.ThumbnailRetryBackoffMs
import com.example.mediacenter.ui.config.ViewTypeFooter
import com.example.mediacenter.ui.config.ViewTypeVideo
import com.example.mediacenter.ui.config.BackdropAlpha
import com.example.mediacenter.ui.config.containsHeader
import com.example.mediacenter.ui.config.findCause
import com.example.mediacenter.ui.config.isRecoverablePlaybackError
import com.example.mediacenter.ui.config.playbackErrorMessage
import com.example.mediacenter.ui.config.playbackMimeType
import com.example.mediacenter.ui.config.playbackRequestHeaders
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.DetailFocusTarget
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusNoCategoryKey
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.ui.theme.SEARCH_HOT_KEYWORDS
import com.example.mediacenter.ui.views.ChevronDownView
import com.example.mediacenter.ui.views.PillIcon
import com.example.mediacenter.ui.views.PillIconView
import com.example.mediacenter.ui.views.ThumbnailImageView
import com.example.mediacenter.ui.views.TransportIcon
import com.example.mediacenter.ui.views.TransportIconView
import com.example.mediacenter.ui.views.WarningGlyphView
import com.example.mediacenter.update.AppUpdateResult
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import com.example.mediacenter.ui.components.appBackground
import com.example.mediacenter.ui.loadBackdropImage
import com.example.mediacenter.ui.home.clearHomeVideoList
import com.example.mediacenter.ui.home.cancelPendingCategorySelect
import com.example.mediacenter.ui.home.VideoGridAdapter
import com.example.mediacenter.ui.home.videoGrid
import com.example.mediacenter.ui.home.prefetchVisibleHomeDetails
import com.example.mediacenter.ui.home.renderHome
import com.example.mediacenter.ui.exitSearchMode
import com.example.mediacenter.ui.homeDefaultCategoryTarget
import com.example.mediacenter.ui.updatePlayerErrorOverlay
import com.example.mediacenter.ui.updatePlayerRetryOverlay
import com.example.mediacenter.ui.details.renderDetails
import com.example.mediacenter.ui.player.renderPlayer
import com.example.mediacenter.ui.showUpdateMessage
import com.example.mediacenter.ui.promptInstallUpdate
import com.example.mediacenter.ui.handleInstallLaunch
import com.example.mediacenter.ui.player.releasePlayer
import com.example.mediacenter.ui.player.resetPlayerOrientationToDefault
import com.example.mediacenter.ui.home.resetHomeDetailsPrefetch
import com.example.mediacenter.ui.loadImage
import com.example.mediacenter.ui.setPlaceholderVisible
import com.example.mediacenter.ui.components.applyCardFocus
import com.example.mediacenter.ui.components.applyPillFocus
import com.example.mediacenter.ui.components.circleIconButton
import com.example.mediacenter.ui.components.glassText
import com.example.mediacenter.ui.components.iconPill
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.detailBackdropScrim
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.formatDurationSeconds
import com.example.mediacenter.ui.components.formatTime
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.gridColumns
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.linearWrap
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.posterCardWidth
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.roundedGradient
import com.example.mediacenter.ui.components.videoMeta
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.components.wrap

class MainActivity : ComponentActivity() {
    internal val viewModel: MediaViewModel by viewModels {
        MediaViewModelFactory(
            MediaRepository(
                api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
            ),
        )
    }

    internal lateinit var root: FrameLayout
    internal var currentState: MediaUiState = MediaUiState()
    internal var renderedState: MediaUiState? = null
    internal var renderedSearchVisible: Boolean = false
    internal var searchVisible: Boolean = false
    internal var player: ExoPlayer? = null
    internal var currentPlayerUrl: String? = null
    // 网络恢复自动重试:legacy 目标是不稳定的旧 TV 盒子,断网→恢复后若停在错误页,
    // 用户必须手动重试。注册 NetworkCallback,网络 onAvailable 且当前是错误态时自动 retry。
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    // 与 currentState.playing?.id 不同：该字段在 renderPlayer 开始时就固定，
    // 不受后续 playNext 等高线状态变化影响，确保 releasePlayer 时存对键。
    internal var currentPlayerOptionId: String? = null
    // 播放页画面旋转。TV 本身不能像手机一样物理转屏,所以对“竖向编码但需要横看”的视频
    // 旋转播放器画面本身。自动恢复重建同一视频时保持手动选择;换视频后重新自动检测。
    internal var playerRotationVideoId: String? = null
    internal var playerVideoRotationDegrees: Int = 0
    internal var playerVideoRotationUserSet: Boolean = false
    internal var playerSurface: PlayerView? = null
    internal var orientationIndicator: TextView? = null
    internal var homeFocusArea: HomeFocusArea = HomeFocusArea.Category
    internal var rememberedCategoryId: String? = null
    internal val rememberedVideoFocusByCategory = mutableMapOf<String, String>()
    internal var pendingHomeFocus: HomeFocusTarget? = null
    internal var pendingCategorySelect: Runnable? = null
    internal var prefetchedVisibleVideoIds: List<String> = emptyList()
    internal var homeVideoRecycler: RecyclerView? = null
    internal var homeVideoAdapter: VideoGridAdapter? = null
    internal var savedGridState: Parcelable? = null
    internal var selectedPlaybackOptionId: String? = null
    internal var selectedPlaybackVideoId: String? = null
    internal var detailFocusTarget: DetailFocusTarget = DetailFocusTarget.Play
    internal val playbackLoadErrorHandlingPolicy = CappedLoadErrorHandlingPolicy(MaxPlaybackRetries)
    // 瞬时 5xx 自动恢复期间盖在播放画面上的“正在重试…”提示,避免重试窗口里只剩黑屏/卡帧。
    internal var playerRetryOverlay: View? = null
    internal var playerErrorOverlay: View? = null
    internal lateinit var appUpdateManager: AppUpdateManager
    internal var pendingUpdateApk: File? = null
    // “播放已稳定”计时器:STATE_READY 后延迟触发;只有同一地址连续顺畅播放超过窗口
    // 才把 ViewModel 的恢复额度清零,防止“就绪即失败”的流陷入无限恢复循环。
    internal var playbackStabilityRunnable: Runnable? = null
    internal val pendingTextUpdates = WeakHashMap<TextView, String>()
    // 系统诊断弹窗触发:焦点已在最左分类时连续按左键的次数,达 5 次触发弹窗。
    internal var leftEdgePressCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Activity", "onCreate restored=${savedInstanceState != null} server=${BuildConfig.MEDIA_SERVER_URL}")

        root = FrameLayout(this)
        setContentView(root)
        appUpdateManager = AppUpdateManager(this, MediaApi(BuildConfig.MEDIA_SERVER_URL))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        currentState.playing != null -> viewModel.back()
                        currentState.selected != null -> {
                            homeFocusArea = HomeFocusArea.Video
                            pendingHomeFocus = currentState.items.firstOrNull()?.let { HomeFocusTarget.Video(it.id) }
                            viewModel.back()
                        }
                        searchVisible -> {
                            searchVisible = false
                            pendingHomeFocus = homeDefaultCategoryTarget(currentState)
                            invalidateUi()
                        }
                        currentState.query.isNotBlank() -> exitSearchMode()
                        homeFocusArea == HomeFocusArea.Video -> {
                            pendingHomeFocus = homeDefaultCategoryTarget(currentState)
                            homeFocusArea = HomeFocusArea.Category
                            invalidateUi()
                        }
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    currentState = state
                    try {
                        render(state)
                    } catch (e: Exception) {
                        AppLog.e("Render", "render failed, resetting for recovery", e)
                        renderedState = null
                    }
                }
            }
        }
        lifecycleScope.launch {
            when (val result = appUpdateManager.checkAndDownload()) {
                AppUpdateResult.NoUpdate -> Unit
                is AppUpdateResult.Failed -> showUpdateMessage(result.message)
                is AppUpdateResult.ReadyToInstall -> promptInstallUpdate(result)
            }
        }
        registerNetworkRestore()
    }

    /**
     * 注册网络恢复监听:网络从断开→恢复(onAvailable)且 ViewModel 当前处于错误态时,
     * 自动触发 retry。legacy 目标是网络不稳定的旧 TV 盒子,这是最高价值体验缺口
     * (断网恢复后无需用户手动重试)。
     *
     * 仅在 Build.VERSION.SDK_INT >= N (API 24) 使用 registerDefaultNetworkCallback;
     * 更老设备降级为不自动重试(不影响功能)。
     */
    private fun registerNetworkRestore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val s = currentState
                    if (s.error != null && s.items.isEmpty() && !s.loading) {
                        AppLog.i("Net", "network restored, auto-retry on error state")
                        viewModel.retry()
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            AppLog.w("Net", "registerDefaultNetworkCallback failed: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingUpdateApk ?: return
        handleInstallLaunch(appUpdateManager.launchInstaller(apk), apk)
    }

    override fun onStop() {
        super.onStop()
        AppLog.i("Activity", "onStop, releasing player")
        player?.pause()
        releasePlayer()
        renderedState = null
    }

    override fun onDestroy() {
        AppLog.i("Activity", "onDestroy")
        // registerNetworkRestore 只在 API 24+ 注册回调;注销也要同样守卫,
        // 否则 unregisterNetworkCallback(API 21+)在更低版本上会崩。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let { cb ->
                runCatching {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    cm?.unregisterNetworkCallback(cb)
                }
                networkCallback = null
            }
        }
        releasePlayer()
        super.onDestroy()
    }

    internal fun invalidateUi() {
        renderedState = null
        // 保存 RecyclerView 滚动位置,重建后恢复(见 videoGrid)
        if (savedGridState == null) {
            savedGridState = homeVideoRecycler?.layoutManager?.onSaveInstanceState()
        }
        render(currentState)
    }

    private fun render(state: MediaUiState) {
        if (renderedState == state && renderedSearchVisible == searchVisible) return
        val previousState = renderedState
        if (previousState != null && updateHomeInPlaceIfPossible(previousState, state)) {
            renderedState = state
            renderedSearchVisible = searchVisible
            return
        }
        renderedState = state
        renderedSearchVisible = searchVisible

        when {
            state.playing != null -> {
                clearHomeVideoList()
                resetHomeDetailsPrefetch()
                renderPlayer(state)
                updatePlayerRetryOverlay(state.loading && state.error == null)
                updatePlayerErrorOverlay(state.error)
            }
            state.selected != null -> {
                clearHomeVideoList()
                resetHomeDetailsPrefetch()
                releasePlayer()
                resetPlayerOrientationToDefault()
                renderDetails(state)
            }
            else -> {
                releasePlayer()
                resetPlayerOrientationToDefault()
                renderHome(state)
            }
        }
    }

    private fun updateHomeInPlaceIfPossible(previous: MediaUiState, state: MediaUiState): Boolean {
        val adapter = homeVideoAdapter ?: return false
        if (renderedSearchVisible != searchVisible || searchVisible) return false
        if (previous.playing != null || previous.selected != null || state.playing != null || state.selected != null) return false
        if (previous.items.isEmpty() || state.items.isEmpty()) return false
        if (previous.query != state.query ||
            previous.categories != state.categories ||
            previous.selectedCategoryId != state.selectedCategoryId ||
            previous.loading != state.loading ||
            previous.autoRetrying != state.autoRetrying ||
            previous.error != state.error
        ) {
            return false
        }

        adapter.setData(
            newItems = state.items,
            newCanLoadMore = state.canLoadMore,
            newLoadingMore = state.loadingMore,
            newLoadMoreError = state.loadMoreError,
            focusTarget = null,
        )
        prefetchVisibleHomeDetails(state.items.take(gridColumns() * 2))
        return true
    }


    internal fun TextView.setTextIfChanged(value: String) {
        if (text?.toString() == value) {
            pendingTextUpdates.remove(this)
            return
        }
        if (isInLayout) {
            pendingTextUpdates[this] = value
            post {
                val pending = pendingTextUpdates.remove(this@setTextIfChanged) ?: return@post
                if (text?.toString() != pending) {
                    text = pending
                }
            }
        } else {
            pendingTextUpdates.remove(this)
            text = value
        }
    }

    // TV 设计密度:以 Android TV 标准设计画布 960×540dp(1080p=xhdpi)为基准,
    // 用真实像素尺寸反推有效密度,使布局尺寸与字号只取决于真实分辨率比例,
    // 不受设备上报 density 影响。在正确上报的 1080p 设备上结果等于系统 density(2.0),
    // 因此对规范设备零副作用;在误报 density 的电视/盒子上则自动校正,
    // 让全部页面在 720p/1080p/4K 上保持一致的 10ft 视觉比例与可读性。
    internal val tvDensity: Float
        get() {
            val metrics = resources.displayMetrics
            val byHeight = metrics.heightPixels / 540f
            val byWidth = metrics.widthPixels / 960f
            val derived = minOf(byHeight, byWidth)
            return if (derived.isFinite() && derived > 0f) derived else metrics.density
        }
}
