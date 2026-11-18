package com.example.mediacenter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.exoplayer2.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.player.PlayerViewHolder
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.MediaViewModel
import com.example.mediacenter.ui.MediaViewModelFactory
import com.example.mediacenter.ui.config.MaxPlaybackRetries
import com.example.mediacenter.ui.theme.DetailFocusTarget
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.update.AppUpdateResult
import com.example.mediacenter.update.AppUpdateManager
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.launch
import com.example.mediacenter.ui.home.clearHomeVideoList
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
import com.example.mediacenter.ui.components.gridColumns

class MainActivity : ComponentActivity() {
    internal val viewModel: MediaViewModel by viewModels {
        MediaViewModelFactory(
            MediaRepository(
                api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
                prefs = getSharedPreferences("media_prefs", Context.MODE_PRIVATE),
                context = applicationContext,
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
    internal var playerSurface: PlayerViewHolder? = null
    internal var playerScreen: FrameLayout? = null
    internal var orientationIndicator: TextView? = null
    internal var homeFocusArea: HomeFocusArea = HomeFocusArea.Category
    internal var rememberedCategoryId: String? = null
    internal val rememberedVideoFocusByCategory = mutableMapOf<String, String>()
    internal var pendingHomeFocus: HomeFocusTarget? = null
    // 首次进入首页只把焦点放到当前分类标签一次；之后从详情/播放/搜索返回
    // 仍按用户最后所在区域恢复，不被强制拉回分类栏。
    internal var homeInitialFocusApplied = false
    internal var pendingCategorySelect: Runnable? = null
    internal var prefetchedVisibleVideoIds: List<String> = emptyList()
    internal var homeVideoRecycler: RecyclerView? = null
    internal var homeVideoAdapter: VideoGridAdapter? = null
    internal var savedGridState: Parcelable? = null
    internal var selectedPlaybackOptionId: String? = null
    // 详情页清晰度 chips 选中的 qn(availableQualities 驱动;null = 未选,用默认清晰度)
    internal var selectedQualityQn: Int? = null
    internal var selectedPlaybackVideoId: String? = null
    internal var detailFocusTarget: DetailFocusTarget = DetailFocusTarget.Play
    internal val playbackLoadErrorHandlingPolicy = CappedLoadErrorHandlingPolicy(MaxPlaybackRetries)
    // 瞬时 5xx 自动恢复期间盖在播放画面上的“正在重试…”提示,避免重试窗口里只剩黑屏/卡帧。
    internal var playerRetryOverlay: View? = null
    // 当前重试遮罩的文案:切清晰度/重试共用同一遮罩,文案变化时重建提示。
    internal var playerRetryOverlayMessage: String? = null
    internal var playerErrorOverlay: View? = null
    // 切清晰度重建播放器前捕获的精确播放位置(-1 表示无):新流 prepare 时直接 seek,
    // 旧实现按旧 option id 保存进度、新 key 读取=0,切档后从 0:00 重播。
    internal var pendingPlaybackSeekPosition: Long = -1L
    internal lateinit var appUpdateManager: AppUpdateManager
    internal var pendingUpdateApk: File? = null
    // 更新已下载完但用户正在播放/看详情:先记下,回到首页再弹安装提示。
    // 旧实现不分页面直接弹框,会盖在播放画面上并抢走遥控器焦点。
    internal var pendingUpdatePrompt: AppUpdateResult.ReadyToInstall? = null
    // 更新检查/下载是否已启动(首页就绪后只启动一次)。
    private var updateCheckStarted = false
    // 页面忙碌时被推迟的更新失败提示(回到首页再弹,避免抢播放/详情焦点)。
    private var pendingUpdateMessage: String? = null
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
                            // 不再钉死到第一个视频：置空 pendingHomeFocus，让
                            // homeFocusTargetFor 走 rememberedVideoFocusByCategory
                            // 恢复用户离开前的卡片(滚动位置由 savedGridState 恢复)。
                            pendingHomeFocus = null
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
        // 更新检查延后到首页内容就绪(见 maybeStartUpdateCheck):启动瞬间下载
        // ~5.8MB APK 会与首屏/缩略图争带宽。这里 12s 兜底,保证首页加载失败/
        // 分类为空时也会检查一次。
        root.postDelayed({ maybeStartUpdateCheck(force = true) }, 12_000)
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
        // 从系统设置/通知侧栏返回时窗口焦点被系统拿走，播放页的 screen
        // 不会重建；这里重新拿回焦点，否则遥控器整个播放页无响应。
        if (currentState.playing != null) playerScreen?.requestFocus()
        val apk = pendingUpdateApk ?: return
        lifecycleScope.launch {
            handleInstallLaunch(appUpdateManager.launchInstaller(apk), apk)
        }
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

        if (state.playing == null) {
            // 离开播放页:清掉换源保进度缓存,避免下次进播放页误用旧位置。
            pendingPlaybackSeekPosition = -1L
        }
        when {
            state.playing != null -> {
                clearHomeVideoList()
                resetHomeDetailsPrefetch()
                renderPlayer(state)
                // 播放等待提示:自动恢复显示"正在重试…",切清晰度显示"正在切换清晰度…";
                // 切集/切分页同样 loading,但不套用这两种文案(避免误导)。
                val loadingHint = when {
                    state.loading && state.error == null && state.recoveringPlayback -> "正在重试…"
                    state.loading && state.error == null && state.switchingQuality -> "正在切换清晰度…"
                    else -> null
                }
                updatePlayerRetryOverlay(loadingHint != null, loadingHint ?: "正在重试…")
                updatePlayerErrorOverlay(state.error)
            }
            state.selected != null -> {
                // 进详情前保存网格滚动位置，返回时 videoGrid 用它恢复
                // (此前只有 invalidateUi 会存，直接进详情会丢滚动位置)。
                if (savedGridState == null) {
                    savedGridState = homeVideoRecycler?.layoutManager?.onSaveInstanceState()
                }
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
                // 首页内容已就绪:此时才开始检查/下载更新(见 maybeStartUpdateCheck)。
                maybeStartUpdateCheck()
                // 播放/详情期间被推迟的更新安装提示:回到首页后再弹,避免在播放画面上
                // 抢焦点(习惯性按 OK 可能误触"安装更新")。
                pendingUpdatePrompt?.let { pending ->
                    pendingUpdatePrompt = null
                    root.post { if (!isFinishing) promptInstallUpdate(pending) }
                }
                pendingUpdateMessage?.let { message ->
                    pendingUpdateMessage = null
                    root.post { if (!isFinishing) showUpdateMessage(message) }
                }
            }
        }
    }

    /**
     * 首页内容就绪后才开始检查/下载更新:避免启动瞬间与首屏、缩略图抢带宽。
     * 播放/详情/搜索页可见时不启动(下载 + 可能的弹窗会打扰观看);离开播放页
     * 回首页的 render 会再次触发(见 render 的 else 分支)。force=true 是启动兜底。
     */
    private fun maybeStartUpdateCheck(force: Boolean = false) {
        if (updateCheckStarted) return
        val state = currentState
        if (state.playing != null || state.selected != null || searchVisible) return
        if (!force && state.items.isEmpty()) return
        updateCheckStarted = true
        lifecycleScope.launch {
            when (val result = appUpdateManager.checkAndDownload()) {
                AppUpdateResult.NoUpdate -> Unit
                is AppUpdateResult.Failed -> {
                    // 失败提示同样延后:播放/详情中弹窗会抢焦点,习惯性按 OK 可能误触。
                    if (currentState.playing != null || currentState.selected != null || searchVisible) {
                        pendingUpdateMessage = result.message
                    } else {
                        showUpdateMessage(result.message)
                    }
                }
                is AppUpdateResult.ReadyToInstall -> presentUpdatePrompt(result)
            }
        }
    }

    /**
     * 弹出更新安装框前的页面判断:播放/详情/搜索层可见时先记下来,回首页再提示。
     * 更新包已下载完,推迟只影响"何时提示",不影响安装流程。
     */
    private fun presentUpdatePrompt(result: AppUpdateResult.ReadyToInstall) {
        if (currentState.playing != null || currentState.selected != null || searchVisible) {
            pendingUpdatePrompt = result
            AppLog.i("Update", "install prompt deferred: content screen visible")
            return
        }
        promptInstallUpdate(result)
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
