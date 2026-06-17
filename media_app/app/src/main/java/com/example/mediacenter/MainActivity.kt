package com.example.mediacenter

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
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
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.isTransientHttpStatusCode
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.player.TolerantDashManifestParser
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.MediaViewModel
import com.example.mediacenter.ui.MediaViewModelFactory
import com.example.mediacenter.update.AppUpdateResult
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

class MainActivity : ComponentActivity() {
    private val viewModel: MediaViewModel by viewModels {
        MediaViewModelFactory(
            MediaRepository(
                api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
            ),
        )
    }

    private lateinit var root: FrameLayout
    private var currentState: MediaUiState = MediaUiState()
    private var renderedState: MediaUiState? = null
    private var renderedSearchVisible: Boolean = false
    private var searchVisible: Boolean = false
    private var player: ExoPlayer? = null
    private var currentPlayerUrl: String? = null
    // 播放页画面旋转。TV 本身不能像手机一样物理转屏,所以对“竖向编码但需要横看”的视频
    // 旋转播放器画面本身。自动恢复重建同一视频时保持手动选择;换视频后重新自动检测。
    private var playerRotationVideoId: String? = null
    private var playerVideoRotationDegrees: Int = 0
    private var playerVideoRotationUserSet: Boolean = false
    private var playerSurface: PlayerView? = null
    private var orientationIndicator: TextView? = null
    private var homeFocusArea: HomeFocusArea = HomeFocusArea.Category
    private var rememberedCategoryId: String? = null
    private val rememberedVideoFocusByCategory = mutableMapOf<String, String>()
    private var pendingHomeFocus: HomeFocusTarget? = null
    private var pendingCategorySelect: Runnable? = null
    private var prefetchedVisibleVideoIds: List<String> = emptyList()
    private var homeVideoRecycler: RecyclerView? = null
    private var homeVideoAdapter: VideoGridAdapter? = null
    private var selectedPlaybackOptionId: String? = null
    private var selectedPlaybackVideoId: String? = null
    private var detailFocusTarget: DetailFocusTarget = DetailFocusTarget.Play
    private val playbackLoadErrorHandlingPolicy = CappedLoadErrorHandlingPolicy(MaxPlaybackRetries)
    // 瞬时 5xx 自动恢复期间盖在播放画面上的“正在重试…”提示,避免重试窗口里只剩黑屏/卡帧。
    private var playerRetryOverlay: View? = null
    private var playerErrorOverlay: View? = null
    private lateinit var appUpdateManager: AppUpdateManager
    private var pendingUpdateApk: File? = null
    // “播放已稳定”计时器:STATE_READY 后延迟触发;只有同一地址连续顺畅播放超过窗口
    // 才把 ViewModel 的恢复额度清零,防止“就绪即失败”的流陷入无限恢复循环。
    private var playbackStabilityRunnable: Runnable? = null
    private val pendingTextUpdates = WeakHashMap<TextView, String>()

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
                        currentState.selected != null -> viewModel.back()
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
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingUpdateApk ?: return
        if (appUpdateManager.canInstallPackages()) {
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
        releasePlayer()
        super.onDestroy()
    }

    private fun invalidateUi() {
        renderedState = null
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

    private fun renderHome(state: MediaUiState) {
        clearHomeVideoList()
        val screen = FrameLayout(this).apply {
            background = appBackground()
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
            clipChildren = false
            clipToPadding = false
        }
        screen.addView(content, frameMatch())
        val focusTarget = homeFocusTargetFor(state)
        var focusRestoreRegistered = false
        fun registerFocusTarget(target: HomeFocusTarget, view: View) {
            view.tag = target.viewTag()
            if (!focusRestoreRegistered && focusTarget == target) {
                focusRestoreRegistered = true
                view.post {
                    if (view.requestFocus() && pendingHomeFocus == target) {
                        pendingHomeFocus = null
                    }
                }
            }
        }

        if (searchVisible) {
            resetHomeDetailsPrefetch()
            content.addView(View(this), LinearLayout.LayoutParams(match(), dp(60)))
            content.addView(searchPanel(state), linearMatch())
            setContent(screen)
            return
        }

        if (state.query.isBlank()) {
            content.addView(
                homeTopBar(state.categories, state.selectedCategoryId, ::registerFocusTarget),
                linearMatch(),
            )
        } else {
            content.addView(searchModeBar(state.query, ::registerFocusTarget), linearMatch())
        }

        val body = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        content.addView(body, LinearLayout.LayoutParams(match(), 0, 1f))
        if (state.items.isEmpty()) {
            resetHomeDetailsPrefetch()
        }
        when {
            state.loading && state.items.isEmpty() -> body.addView(centeredLoading("正在加载…"), frameMatch())
            state.error != null && state.items.isEmpty() -> body.addView(errorState(state.error, viewModel::retry), frameMatch())
            state.items.isEmpty() -> body.addView(emptyState(if (state.query.isBlank()) "当前分类暂无内容" else "没有找到相关内容"), frameMatch())
            else -> {
                body.addView(
                    videoGrid(
                        items = state.items,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        loadMoreError = state.loadMoreError,
                        onRetryLoadMore = viewModel::retryLoadMore,
                        focusTarget = focusTarget,
                    ),
                    frameMatch(),
                )
                prefetchVisibleHomeDetails(state.items.take(gridColumns() * 2))
                state.error?.let { message ->
                    body.addView(
                        homeInlineError(message),
                        FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM).apply {
                            setMargins(dp(8), 0, dp(8), dp(20))
                        },
                    )
                }
            }
        }

        if (state.loading && state.items.isNotEmpty()) {
            screen.addView(loadingScrim(), FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }

        setContent(screen)
    }

    private fun homeTopBar(
        categories: List<ContentCategory>,
        selectedId: String?,
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(10))
            clipChildren = false
            clipToPadding = false
            // 分类栏保持透明，只让 chip / 搜索按钮自身提供玻璃底；主体列表在下方裁剪。
            elevationCompat(dp(20).toFloat())
        }

        val searchButton = circleIconButton(
            icon = PillIcon.Search,
            onClick = {
                cancelPendingCategorySelect()
                homeFocusArea = HomeFocusArea.Search
                searchVisible = true
                invalidateUi()
            },
            onFocusChange = { focused ->
                if (focused) {
                    cancelPendingCategorySelect()
                    homeFocusArea = HomeFocusArea.Search
                }
            },
        ).apply {
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        currentState.items.firstOrNull()?.id
                            ?.let(HomeFocusTarget::Video)
                            ?.let { target ->
                                homeFocusArea = HomeFocusArea.Video
                                requestHomeFocus(target)
                            } ?: false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        categories.lastOrNull()?.id?.let { id ->
                            homeFocusArea = HomeFocusArea.Category
                            requestHomeFocus(HomeFocusTarget.Category(id))
                        } ?: false
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }
        registerFocusTarget(HomeFocusTarget.Search, searchButton)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            clipChildren = false
            clipToPadding = false
        }
        categories.forEachIndexed { index, category ->
            val chip = pill(
                text = category.title,
                selected = category.id == selectedId,
                heightDp = 44,
                horizontalPaddingDp = 22,
                onClick = {
                    cancelPendingCategorySelect()
                    searchVisible = false
                    rememberedCategoryId = category.id
                    homeFocusArea = HomeFocusArea.Category
                    pendingHomeFocus = HomeFocusTarget.Category(category.id)
                    viewModel.selectCategory(category)
                },
                onFocusChange = { focused ->
                    if (focused) {
                        rememberedCategoryId = category.id
                        homeFocusArea = HomeFocusArea.Category
                        // 聚焦即加载该分类:无需额外按 OK 确认。用防抖避免
                        // 左右快速划过分类时每个都触发网络请求,只在停留时切换。
                        scheduleCategorySelect(category)
                    }
                },
            ).apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // 进入下方视频网格前,取消任何挂起的分类切换防抖,
                            // 避免迟到的切换在用户已进入网格后触发整屏重建、弹走焦点。
                            cancelPendingCategorySelect()
                            val target = rememberedVideoFocusByCategory[homeCategoryKey(currentState)]
                                ?.let(HomeFocusTarget::Video)
                                ?: currentState.items.firstOrNull()?.id?.let(HomeFocusTarget::Video)
                            if (target != null) {
                                homeFocusArea = HomeFocusArea.Video
                                requestHomeFocus(target)
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> index == 0
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index == categories.lastIndex) {
                                cancelPendingCategorySelect()
                                homeFocusArea = HomeFocusArea.Search
                                requestHomeFocus(HomeFocusTarget.Search)
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            }
            registerFocusTarget(HomeFocusTarget.Category(category.id), chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(12)))
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            addView(chipRow)
        }
        row.addView(scroll, LinearLayout.LayoutParams(0, wrap(), 1f))
        row.addView(searchButton, LinearLayout.LayoutParams(dp(54), dp(54)).withStartMargin(dp(14)))
        return row
    }

    private fun searchModeBar(
        query: String,
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(14))
            clipChildren = false
            clipToPadding = false
        }
        val queryButton = glassText(
            "搜索：$query",
            17f,
            Colors.TextPrimary,
            onFocusChange = { focused ->
                if (focused) homeFocusArea = HomeFocusArea.Search
            },
        ).apply {
            setOnClickListener {
                homeFocusArea = HomeFocusArea.Search
                searchVisible = true
                invalidateUi()
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    currentState.items.firstOrNull()?.id
                        ?.let(HomeFocusTarget::Video)
                        ?.let { target ->
                            homeFocusArea = HomeFocusArea.Video
                            requestHomeFocus(target)
                        } ?: false
                } else {
                    false
                }
            }
        }
        registerFocusTarget(HomeFocusTarget.Search, queryButton)
        row.addView(queryButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        row.addView(
            iconPill(
                text = "清除",
                icon = PillIcon.Close,
                selected = false,
                heightDp = 54,
                horizontalPaddingDp = 22,
                onClick = { exitSearchMode() },
                onFocusChange = { focused ->
                    if (focused) homeFocusArea = HomeFocusArea.Search
                },
            ),
            LinearLayout.LayoutParams(wrap(), dp(54)).withStartMargin(dp(14)),
        )
        row.getChildAt(1)?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                currentState.items.firstOrNull()?.id
                    ?.let(HomeFocusTarget::Video)
                    ?.let { target ->
                        homeFocusArea = HomeFocusArea.Video
                        requestHomeFocus(target)
                    } ?: false
            } else {
                false
            }
        }
        return row
    }

    private fun searchPanel(state: MediaUiState): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val inputWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = false
            isClickable = true
        }
        val inputIcon = PillIconView(this, PillIcon.Search).apply {
            setIconColor(Colors.TextTertiary)
        }
        inputWrap.addView(inputIcon, LinearLayout.LayoutParams(dp(22), dp(22)).withEndMargin(dp(14)))
        val input = EditText(this).apply {
            setText(state.query)
            hint = "输入关键词"
            setSingleLine(true)
            setTextColor(Colors.TextPrimary)
            setHintTextColor(Colors.TextTertiary)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * tvDensity)
            setPadding(0, 0, 0, 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { view, focused ->
                inputWrap.background = rounded(
                    if (focused) Colors.GlassFillStrong else Colors.GlassFill,
                    999,
                    if (focused) 2 else 1,
                    if (focused) Colors.AppleBlue else Colors.GlassBorder,
                )
                inputIcon.setIconColor(if (focused) Colors.TextPrimary else Colors.TextTertiary)
            }
        }
        fun submitSearch(rawKeyword: String): Boolean {
            val keyword = rawKeyword.trim()
            if (keyword.isBlank()) {
                input.requestFocus()
                return true
            }
            searchVisible = false
            homeFocusArea = HomeFocusArea.Video
            pendingHomeFocus = null
            viewModel.search(keyword)
            return true
        }
        inputWrap.setOnClickListener { input.requestFocus() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                submitSearch(input.text.toString())
            } else {
                false
            }
        }
        inputWrap.addView(input, LinearLayout.LayoutParams(0, wrap(), 1f))
        row.addView(inputWrap, LinearLayout.LayoutParams(0, dp(58), 1f))

        val searchButton = primaryPill("搜索", heightDp = 48, horizontalPaddingDp = 30) {
            submitSearch(input.text.toString())
        }
        var firstKeywordChip: View? = null
        row.addView(searchButton, LinearLayout.LayoutParams(wrap(), dp(48)).withStartMargin(dp(14)))
        val cancelButton = pill("取消", selected = false, heightDp = 58, horizontalPaddingDp = 28) {
            searchVisible = false
            pendingHomeFocus = homeDefaultCategoryTarget(currentState)
            invalidateUi()
        }
        row.addView(
            cancelButton,
            LinearLayout.LayoutParams(wrap(), dp(58)).withStartMargin(dp(12)),
        )
        searchButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    input.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cancelButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    firstKeywordChip?.requestFocus() == true
                }
                else -> false
            }
        }
        cancelButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    input.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    searchButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    firstKeywordChip?.requestFocus() == true
                }
                else -> false
            }
        }
        panel.addView(row, linearMatch())

        panel.addView(label("热门关键词", 13f, Colors.TextTertiary, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(28)))
        val keywordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        SEARCH_HOT_KEYWORDS.forEach { keyword ->
            val chip = pill(keyword, selected = false, heightDp = 44, horizontalPaddingDp = 20) {
                submitSearch(keyword)
            }.apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        searchButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            if (firstKeywordChip == null) firstKeywordChip = chip
            keywordRow.addView(
                chip,
                LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(10)),
            )
        }
        panel.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(keywordRow)
            },
            linearMatch(),
        )
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                searchButton.requestFocus()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                submitSearch(input.text.toString())
            } else {
                false
            }
        }
        panel.post { input.requestFocus() }
        return panel
    }

    private fun clearHomeVideoList() {
        cancelPendingCategorySelect()
        homeVideoRecycler?.adapter = null
        homeVideoRecycler = null
        homeVideoAdapter = null
    }

    private fun scheduleCategorySelect(category: ContentCategory) {
        // 已是当前分类则无需切换(selectCategory 内部也会去重,这里提前跳过省去防抖)。
        if (category.id == currentState.selectedCategoryId) {
            cancelPendingCategorySelect()
            return
        }
        cancelPendingCategorySelect()
        val runnable = Runnable {
            pendingCategorySelect = null
            // 只有焦点仍停留在分类 chip 行(Category 区域)时才真正切换分类。
            // 关键修复:若用户已经按方向键进入下方视频网格(Video 区域)或搜索区,
            // 这个迟到的防抖切换必须放弃——否则它会清空列表、loading 转圈、触发整屏重建,
            // 把用户从正在浏览的视频网格里弹回分类行/首页。
            if (!searchVisible &&
                homeFocusArea == HomeFocusArea.Category &&
                currentState.selected == null &&
                currentState.playing == null &&
                rememberedCategoryId == category.id
            ) {
                pendingHomeFocus = HomeFocusTarget.Category(category.id)
                viewModel.selectCategory(category)
            }
        }
        pendingCategorySelect = runnable
        root.postDelayed(runnable, CategorySelectDebounceMs)
    }

    private fun cancelPendingCategorySelect() {
        pendingCategorySelect?.let { root.removeCallbacks(it) }
        pendingCategorySelect = null
    }

    private fun resetHomeDetailsPrefetch() {
        if (prefetchedVisibleVideoIds.isEmpty()) return
        prefetchedVisibleVideoIds = emptyList()
    }

    private fun prefetchVisibleHomeDetails(videos: List<VideoSummary>) {
        val ids = videos.map { it.id }
        if (ids == prefetchedVisibleVideoIds) return
        if (ids.isEmpty()) {
            resetHomeDetailsPrefetch()
            return
        }
        prefetchedVisibleVideoIds = ids
    }

    private fun videoGrid(
        items: List<VideoSummary>,
        canLoadMore: Boolean,
        loadingMore: Boolean,
        loadMoreError: String?,
        onRetryLoadMore: () -> Unit,
        focusTarget: HomeFocusTarget?,
    ): View {
        val columns = gridColumns()
        val cardWidth = posterCardWidth(columns)
        val cardHeight = (cardWidth * 1.50f).toInt()
        val adapter = VideoGridAdapter(
            columns = columns,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            onRetryLoadMore = onRetryLoadMore,
        )
        val layoutManager = GridLayoutManager(this, columns).apply {
            isItemPrefetchEnabled = false
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isFooterPosition(position)) columns else 1
                }
            }
        }
        val recycler = RecyclerView(this).apply {
            this.layoutManager = layoutManager
            this.adapter = adapter
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            // clipToPadding=false 让首/末行聚焦放大有呼吸空间;clipChildren=true 把
            // 滚动出顶部的卡片裁掉,避免内容向上溢出盖住分类栏(分类栏是 LinearLayout
            // 中更靠前的兄弟,默认会被靠后的列表绘制覆盖)。顶部 padding 已留出聚焦
            // 放大所需的余量,首行 1.06x 放大不会被裁。
            clipToPadding = false
            clipChildren = true
            setPadding(0, dp(16), 0, dp(32))
            itemAnimator = null
            setItemViewCacheSize(0)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= adapter.videoCount - columns) {
                        // loadMore() 会同步把 loadingMore=true 推给 StateFlow,
                        // 触发 render()->setData()->notifyItemChanged()。若直接在滚动回调里
                        // 调用,会在 RecyclerView 滚动派发期间修改适配器而抛 IllegalStateException。
                        // post 到下一帧,等本次滚动/布局派发结束后再触发。
                        recyclerView.post { viewModel.loadMore() }
                    }
                }
            })
        }
        homeVideoRecycler = recycler
        homeVideoAdapter = adapter
        adapter.setData(
            newItems = items,
            newCanLoadMore = canLoadMore,
            newLoadingMore = loadingMore,
            newLoadMoreError = loadMoreError,
            focusTarget = focusTarget,
        )
        if (focusTarget is HomeFocusTarget.Video) {
            adapter.indexOfVideo(focusTarget.id).takeIf { it >= 0 }?.let { index ->
                recycler.scrollToPosition(index)
            }
        }
        return recycler
    }

    private fun loadMoreIndicator(loadingMore: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 24, 1, Colors.GlassBorder)
        }
        if (loadingMore) {
            row.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(label("正在加载更多…", 14f, Colors.TextSecondary, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        } else {
            row.addView(ChevronDownView(this), LinearLayout.LayoutParams(dp(16), dp(16)))
            row.addView(label("下滑加载更多", 14f, Colors.TextTertiary, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(8)))
        }
        return row
    }

    private fun loadMoreErrorIndicator(message: String, onRetry: () -> Unit): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 24, 1, Colors.GlassBorder)
            isFocusable = true
            isClickable = true
            setOnClickListener { onRetry() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
            addView(label(message, 14f, Colors.TextSecondary, bold = true, maxLines = 1), LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(label("重试", 14f, Colors.AppleBlue, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        }
    }

    private inner class VideoGridAdapter(
        private val columns: Int,
        private val cardWidth: Int,
        private val cardHeight: Int,
        private val onRetryLoadMore: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<VideoSummary> = emptyList()
        private var canLoadMore: Boolean = false
        private var loadingMore: Boolean = false
        private var loadMoreError: String? = null
        private var pendingFocusTarget: HomeFocusTarget.Video? = null

        val videoCount: Int
            get() = items.size

        init {
            setHasStableIds(true)
        }

        fun setData(
            newItems: List<VideoSummary>,
            newCanLoadMore: Boolean,
            newLoadingMore: Boolean,
            newLoadMoreError: String?,
            focusTarget: HomeFocusTarget?,
        ) {
            val oldItems = items
            val oldCanLoadMore = canLoadMore
            val oldLoadingMore = loadingMore
            val oldLoadMoreError = loadMoreError
            if (focusTarget is HomeFocusTarget.Video) {
                pendingFocusTarget = focusTarget
            }

            items = newItems
            canLoadMore = newCanLoadMore
            loadingMore = newLoadingMore
            loadMoreError = newLoadMoreError

            val samePrefix = oldItems.size <= newItems.size &&
                oldItems.indices.all { index -> oldItems[index] == newItems[index] }
            if (!samePrefix) {
                notifyDataSetChanged()
                return
            }
            val insertedCount = newItems.size - oldItems.size
            if (insertedCount > 0) {
                notifyItemRangeInserted(oldItems.size, insertedCount)
            }
            when {
                oldCanLoadMore && newCanLoadMore -> {
                    if (insertedCount > 0 || oldLoadingMore != newLoadingMore || oldLoadMoreError != newLoadMoreError) {
                        notifyItemChanged(items.size)
                    }
                }
                oldCanLoadMore && !newCanLoadMore -> notifyItemRemoved(items.size)
                !oldCanLoadMore && newCanLoadMore -> notifyItemInserted(items.size)
            }
        }

        fun requestFocusOn(target: HomeFocusTarget.Video) {
            pendingFocusTarget = target
            val index = indexOfVideo(target.id)
            if (index >= 0) notifyItemChanged(index)
        }

        fun indexOfVideo(videoId: String): Int {
            return items.indexOfFirst { it.id == videoId }
        }

        fun isFooterPosition(position: Int): Boolean {
            return canLoadMore && position == items.size
        }

        override fun getItemCount(): Int {
            return items.size + if (canLoadMore) 1 else 0
        }

        override fun getItemId(position: Int): Long {
            return if (isFooterPosition(position)) {
                FooterStableId
            } else {
                stableVideoId(items[position].id)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (isFooterPosition(position)) ViewTypeFooter else ViewTypeVideo
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == ViewTypeFooter) {
                LoadMoreHolder()
            } else {
                VideoHolder()
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is LoadMoreHolder) {
                holder.bind(loadingMore, loadMoreError)
            } else if (holder is VideoHolder) {
                holder.bind(items[position], position, items.size)
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is VideoHolder) {
                holder.loadVisibleThumbnail()
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                holder.cancelVisibleThumbnail()
            }
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                holder.recycle()
            }
            super.onViewRecycled(holder)
        }

        private fun stableVideoId(value: String): Long {
            var result = 1125899906842597L
            value.forEach { char -> result = 31 * result + char.code }
            return result
        }

        private inner class VideoHolder : RecyclerView.ViewHolder(createVideoCardRoot()) {
            private val card: FrameLayout = itemView as FrameLayout
            private val imageWrap = FrameLayout(this@MainActivity).apply {
                background = roundedGradient(
                    Colors.PlaceholderTop,
                    Colors.PlaceholderBottom,
                    16,
                    1,
                    Colors.GlassBorder,
                )
            }
            private val image = ThumbnailImageView(this@MainActivity)
            private val initials = label("", 42f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
                alpha = 1f
            }
            private val title = label("", 14f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            private val meta = label("", 11f, Color.argb(170, 255, 255, 255), maxLines = 1)
            private val focusIndicator = View(this@MainActivity).apply {
                background = rounded(Colors.AppleBlue, 999)
                alpha = 0f
            }
            private var boundVideo: VideoSummary? = null
            private var boundThumbnailUrl: String? = null

            init {
                imageWrap.addView(image, frameMatch())
                imageWrap.addView(initials, frameMatch())
                imageWrap.addView(
                    View(this@MainActivity).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.argb(22, 255, 255, 255), Color.TRANSPARENT),
                        )
                    },
                    frameMatch(),
                )
                imageWrap.addView(
                    View(this@MainActivity).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.TRANSPARENT, Color.argb(220, 0, 0, 0)),
                        )
                    },
                    frameMatch(),
                )
                imageWrap.addView(
                    focusIndicator,
                    FrameLayout.LayoutParams(match(), dp(4), Gravity.BOTTOM).apply {
                        leftMargin = dp(12)
                        rightMargin = dp(12)
                        bottomMargin = dp(0)
                    },
                )
                card.addView(imageWrap, frameMatch())

                val textBlock = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(dp(10), dp(8), dp(10), dp(10))
                    addView(title, linearMatch())
                    addView(meta, linearMatch())
                }
                imageWrap.addView(textBlock, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

                applyCardFocus(card) { focused ->
                    imageWrap.background = roundedGradient(
                        if (focused) Colors.SurfaceFocused else Colors.PlaceholderTop,
                        if (focused) Colors.SurfaceFocused else Colors.PlaceholderBottom,
                        16,
                        if (focused) 2 else 1,
                        if (focused) Colors.GlassBorderFocused else Colors.GlassBorder,
                    )
                    imageWrap.elevationCompat(if (focused) dp(14).toFloat() else 0f)
                    val video = boundVideo
                    if (focused && video != null) {
                        homeFocusArea = HomeFocusArea.Video
                        rememberedVideoFocusByCategory[homeCategoryKey(currentState)] = video.id
                    }
                    focusIndicator.animate().cancel()
                    focusIndicator.animate().alpha(if (focused) 1f else 0f).setDuration(140).start()
                }
            }

            fun bind(video: VideoSummary, index: Int, itemCount: Int) {
                boundVideo = video
                card.tag = HomeFocusTarget.Video(video.id).viewTag()
                title.text = video.title
                meta.text = videoMeta(video)
                initials.text = video.title.take(2).ifBlank { "影" }
                boundThumbnailUrl = video.thumbnailUrl
                image.cancelThumbnailLoad()
                image.setThumbnailBitmap(null)
                setPlaceholderVisible(initials, true)
                if (card.isAttachedToWindow) {
                    loadVisibleThumbnail()
                }
                card.setOnClickListener {
                    cancelPendingCategorySelect()
                    viewModel.openDetails(video)
                }
                card.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    // loadMore 追加后,bind 时捕获的 index/itemCount 会变陈旧,
                    // 用实时的 binding 位置和适配器数量判断边界,避免中部 item 被
                    // 误判为末项而挡住右移。
                    val pos = bindingAdapterPosition.takeIf { it >= 0 } ?: index
                    val count = videoCount
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos >= columns) return@setOnKeyListener false
                            homeDefaultCategoryTarget(currentState)?.let { target ->
                                homeFocusArea = HomeFocusArea.Category
                                requestHomeFocus(target)
                            } ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> pos % columns == 0
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            pos == count - 1 || pos % columns == columns - 1
                        }
                        else -> false
                    }
                }

                val target = pendingFocusTarget
                if (target != null && target.id == video.id) {
                    pendingFocusTarget = null
                    card.post {
                        if (card.requestFocus()) pendingHomeFocus = null
                    }
                }
            }

            fun loadVisibleThumbnail() {
                val video = boundVideo ?: return
                loadImage(
                    url = boundThumbnailUrl,
                    headers = emptyMap(),
                    image = image,
                    placeholder = initials,
                    title = video.title,
                    maxWidthPx = cardWidth,
                    maxHeightPx = cardHeight,
                )
            }

            fun cancelVisibleThumbnail() {
                image.cancelThumbnailLoad()
                image.setThumbnailBitmap(null)
                setPlaceholderVisible(initials, true)
            }

            fun recycle() {
                cancelVisibleThumbnail()
                boundVideo = null
                boundThumbnailUrl = null
            }
        }

        private inner class LoadMoreHolder : RecyclerView.ViewHolder(createLoadMoreRoot()) {
            private val container: FrameLayout = itemView as FrameLayout

            fun bind(loading: Boolean, error: String?) {
                container.removeAllViews()
                container.addView(
                    if (error == null) {
                        loadMoreIndicator(loading)
                    } else {
                        loadMoreErrorIndicator(error, onRetryLoadMore)
                    },
                    frameMatch(),
                )
            }
        }

        private fun createVideoCardRoot(): FrameLayout {
            return FrameLayout(this@MainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(cardWidth, cardHeight).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(18))
                }
                clipChildren = false
                isFocusable = true
                isClickable = true
                background = rounded(Color.TRANSPARENT, 16, 1, Color.TRANSPARENT)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
        }

        private fun createLoadMoreRoot(): FrameLayout {
            return FrameLayout(this@MainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(match(), dp(72)).apply {
                    setMargins(0, dp(4), 0, dp(18))
                }
            }
        }
    }

    private fun renderDetails(state: MediaUiState) {
        val details = state.selected ?: return
        if (selectedPlaybackVideoId != details.id) {
            selectedPlaybackVideoId = details.id
            selectedPlaybackOptionId = null
            detailFocusTarget = DetailFocusTarget.Play
        }
        val screen = FrameLayout(this).apply { background = appBackground() }
        // 详情页电影感背景:用海报的重度降采样版本作为全屏模糊背景 + 多层 scrim,
        // 既呼应内容又保证右侧信息区文字可读(签名 URL 不落盘,仅内存渲染)。
        val backdrop = ThumbnailImageView(this).apply { alpha = 0f }
        screen.addView(backdrop, frameMatch())
        screen.addView(detailBackdropScrim(), frameMatch())
        loadBackdropImage(details.thumbnailUrl, emptyMap(), backdrop)
        // 详情页采用 Android TV / Material 设计规范的固定 dp/sp 值:
        // 标题 30sp(headline)、正文 18sp、说明 14sp(caption,均 ≥ TV 最小可读字号),
        // 主操作与可聚焦控件 ≥ 48dp 焦点目标,页边距满足 48/27dp overscan 安全区。
        // 这些固定值由 tvDensity 统一换算,因此在任意分辨率/上报 density 下都按比例缩放,
        // 既符合规范又能灵活适配所有设备,无需写死像素或临时比例。
        val hPad = dp(56)
        val vPad = dp(40)
        val heroEndGap = dp(44)
        val heroW = minOf(dp(480), (resources.displayMetrics.widthPixels * 0.42f).toInt())
        val heroH = (heroW / 1.6f).toInt()
        val titleSize = 30f
        val descSize = 18f
        val metaSize = 14f
        val playTextSize = 20f
        val descLines = 3
        val playH = 56
        val chipH = 48
        val playHPad = 34
        val chipHPad = 22
        val gapTitle = 10
        val gapDesc = 14
        val gapDisclosure = 10
        val gapPlay = 22
        val gapQuality = 18
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            clipChildren = false
        }
        screen.addView(row, frameMatch())
        var detailFocusRegistered = false
        fun registerDetailFocus(target: DetailFocusTarget, view: View) {
            if (!detailFocusRegistered && detailFocusTarget == target) {
                detailFocusRegistered = true
                view.post { view.requestFocus() }
            }
        }

        val hero = FrameLayout(this).apply {
            background = roundedGradient(
                Colors.PlaceholderTop,
                Colors.PlaceholderBottom,
                22,
                1,
                Colors.GlassBorder,
            )
            elevationCompat(dp(22).toFloat())
        }
        val heroImage = ThumbnailImageView(this)
        val heroInitials = label(details.title.take(2).ifBlank { "影" }, 64f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            alpha = 1f
        }
        hero.addView(heroImage, frameMatch())
        hero.addView(heroInitials, frameMatch())
        loadImage(
            url = details.thumbnailUrl,
            headers = emptyMap(),
            image = heroImage,
            placeholder = heroInitials,
            title = details.title,
            maxWidthPx = heroW,
            maxHeightPx = heroH,
        )
        hero.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
                )
            },
            frameMatch(),
        )
        row.addView(hero, LinearLayout.LayoutParams(heroW, heroH).withEndMargin(heroEndGap))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        row.addView(info, LinearLayout.LayoutParams(0, wrap(), 1f))

        val metadata = listOfNotNull(
            details.durationSeconds?.let { formatDurationSeconds(it) },
        ).joinToString(" · ")
        if (metadata.isNotBlank()) {
            info.addView(
                label(metadata, metaSize, Colors.TextTertiary, bold = true, maxLines = 1).apply {
                    letterSpacingCompat(0.04f)
                },
                linearMatch(),
            )
        }
        info.addView(
            label(details.title, titleSize, Colors.TextPrimary, bold = true, maxLines = 2).apply {
                letterSpacingCompat(-0.01f)
            },
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(if (metadata.isNotBlank()) gapTitle else 0)),
        )
        info.addView(
            label(details.description.ifBlank { "暂无简介" }, descSize, Colors.TextSecondary, maxLines = descLines).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapDesc)),
        )

        if (details.description.length > 96) {
            info.addView(
                pill("查看完整简介", selected = false, heightDp = chipH, horizontalPaddingDp = chipHPad) {
                    showDescriptionDialog(details.title, details.description)
                },
                LinearLayout.LayoutParams(wrap(), dp(chipH)).withTopMargin(dp(gapDisclosure)),
            )
        }

        val options = details.playbackOptions
        var detailErrorRetry: () -> Unit = viewModel::refreshSelectedPlayback
        if (options.isEmpty()) {
            info.addView(label("暂无可播放视频", 15f, Colors.TextTertiary, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapPlay)))
            if (detailFocusTarget == DetailFocusTarget.Play) {
                detailFocusTarget = DetailFocusTarget.Refresh
            }
            val refresh = pill(
                "刷新播放地址",
                selected = false,
                heightDp = chipH,
                horizontalPaddingDp = chipHPad,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Refresh
                },
                onClick = {
                    detailFocusTarget = DetailFocusTarget.Play
                    viewModel.refreshSelectedPlayback()
                },
            )
            registerDetailFocus(DetailFocusTarget.Refresh, refresh)
            info.addView(refresh, LinearLayout.LayoutParams(wrap(), dp(chipH)).withTopMargin(dp(12)))
        } else {
            val selectedOption = options.firstOrNull { it.id == selectedPlaybackOptionId } ?: options.first()
            selectedPlaybackOptionId = selectedOption.id
            detailErrorRetry = { viewModel.play(selectedOption) }
            val playButton = primaryPill(
                text = buildString {
                    append("播放")
                    selectedOption.label.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                heightDp = playH,
                horizontalPaddingDp = playHPad,
                textSizeSp = playTextSize,
                icon = PillIcon.Play,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Play
                },
                onClick = { viewModel.play(selectedOption) },
            )
            registerDetailFocus(DetailFocusTarget.Play, playButton)
            info.addView(playButton, LinearLayout.LayoutParams(wrap(), dp(playH)).withTopMargin(dp(gapPlay)))

            // 多P选集:分P数量多时用导航+弹窗替代水平一排按钮
            state.currentPages?.let { pages ->
                if (pages.totalPages > 1) {
                    info.addView(label("选集 · 第${pages.currentPage + 1}/${pages.totalPages}集", metaSize, Colors.TextTertiary, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapQuality)))
                    val pageRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        clipChildren = false
                        clipToPadding = false
                        setPadding(0, dp(gapDisclosure), 0, dp(4))
                    }
                    val canPrev = pages.currentPage > 0
                    val canNext = pages.currentPage < pages.totalPages - 1
                    if (canPrev) {
                        val chip = pill("上一集", selected = false, heightDp = chipH, horizontalPaddingDp = chipHPad) {
                            selectedPlaybackOptionId = null
                            viewModel.selectPage(pages.currentPage - 1)
                        }
                        pageRow.addView(chip, LinearLayout.LayoutParams(wrap(), dp(chipH)).withEndMargin(dp(12)))
                    }
                    if (canNext) {
                        val chip = pill("下一集", selected = false, heightDp = chipH, horizontalPaddingDp = chipHPad) {
                            selectedPlaybackOptionId = null
                            viewModel.selectPage(pages.currentPage + 1)
                        }
                        pageRow.addView(chip, LinearLayout.LayoutParams(wrap(), dp(chipH)).withEndMargin(dp(12)))
                    }
                    val allChip = pill("查看全部", selected = false, heightDp = chipH, horizontalPaddingDp = chipHPad) {
                        showAllPagesDialog(pages, details.id)
                    }
                    pageRow.addView(allChip, LinearLayout.LayoutParams(wrap(), dp(chipH)))
                    if (pageRow.childCount > 0) {
                        info.addView(pageRow, linearMatch())
                    }
                }
            }

            if (options.size > 1) {
                info.addView(label("清晰度", metaSize, Colors.TextTertiary, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapQuality)))
                val optionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(0, dp(gapDisclosure), 0, dp(4))
                }
                options.take(5).forEach { option ->
                    val chip = pill(
                        text = option.label.ifBlank { "播放" },
                        selected = option.id == selectedOption.id,
                        heightDp = chipH,
                        horizontalPaddingDp = chipHPad,
                        onFocusChange = { focused ->
                            if (focused) detailFocusTarget = DetailFocusTarget.Option(option.id)
                        },
                        onClick = {
                            selectedPlaybackOptionId = option.id
                            detailFocusTarget = DetailFocusTarget.Option(option.id)
                            invalidateUi()
                        },
                    )
                    registerDetailFocus(DetailFocusTarget.Option(option.id), chip)
                    optionRow.addView(
                        chip,
                        LinearLayout.LayoutParams(wrap(), dp(chipH)).withEndMargin(dp(14)),
                    )
                }
                info.addView(optionRow, linearMatch())
            }
        }

        state.error?.let {
            info.addView(errorInline(it, detailErrorRetry), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(24)))
        }

        if (state.loading) {
            screen.addView(loadingScrim(), FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }
        setContent(screen)
    }

    @OptIn(UnstableApi::class)
    private fun renderPlayer(state: MediaUiState) {
        val stream = state.playing ?: return
        // 恢复流程会先保持旧 stream 并显示 loading,延迟后再刷新播放地址。
        // 这段期间不要因播放器已进入 IDLE 而立刻重建同一个失败 URL。
        if (currentPlayerUrl == stream.url && root.childCount > 0) {
            if (state.loading || state.error != null || player?.playbackState != Player.STATE_IDLE) return
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

        releasePlayer()
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
            builtPlayer!!.apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(playbackUrl)
                    .setMimeType(playbackMimeType(stream.mimeType))
                    .build()
                if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD) {
                    setMediaSource(
                        DashMediaSource.Factory(dataSourceFactory)
                            .setManifestParser(TolerantDashManifestParser())
                            .setLoadErrorHandlingPolicy(playbackLoadErrorHandlingPolicy)
                            .createMediaSource(mediaItem),
                    )
                } else {
                    setMediaItem(mediaItem)
                }
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            // 就绪后才开始计稳定计时;持续顺畅播放超过窗口才清零恢复额度,
                            // 避免“就绪即失败”的流靠 STATE_READY 反复复位计数器造成无限恢复循环。
                            Player.STATE_READY -> {
                                AppLog.i("Player", "stream ${stream.id} READY")
                                schedulePlaybackStabilityReset(stream.id)
                            }
                            Player.STATE_ENDED -> {
                                AppLog.i("Player", "stream ${stream.id} ENDED -> playNext")
                                cancelPlaybackStabilityReset()
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
                AppLog.i("PlayerKey", "code=$keyCode action=${event.action} repeat=${event.repeatCount} long=${event.isLongPress} dt=${event.eventTime - centerDownTime}")
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
                    // 部分遥控器/机型若仍能派发 MENU/INFO,则作为长按 OK 之外的备用入口。
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        if (event.repeatCount == 0) {
                            togglePlayerOrientation()
                            showOverlay()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val playerView = (layoutInflater.inflate(R.layout.view_player_texture, screen, false) as PlayerView).apply {
            player = nextPlayer
            useController = false
            setBackgroundColor(Color.BLACK)
            // FIT 会按视频真实宽高比自动 letterbox/pillarbox;旋转时外层会交换视图宽高。
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerSurface = playerView
        screen.addView(playerView, FrameLayout.LayoutParams(match(), match(), Gravity.CENTER))
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPlayerVideoRotation() }
        // 缓冲指示:独立于自动隐藏的控制层,只要播放器处于 BUFFERING 就常驻显示,
        // 避免控制层淡出后用户以为卡死。
        val bufferingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Colors.AppleBlue)
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

    private fun playerOverlay(stream: PlaybackOption, player: ExoPlayer, state: MediaUiState): FrameLayout {
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
            label(videoTitle, 26f, Colors.TextPrimary, bold = true, maxLines = 2).apply {
                letterSpacingCompat(-0.01f)
            },
            linearMatch(),
        )
        val statusDot = View(this).apply { background = rounded(Colors.AppleBlue, 999) }
        val statusText = label("", 15f, Colors.TextSecondary, bold = true, maxLines = 1)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).withEndMargin(dp(10)))
            addView(statusText, linearWrap())
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
                setIconColor(Colors.TextPrimary)
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
                label("画面 · ${videoRotationLabel()}", 13f, Colors.TextTertiary, maxLines = 1)
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
            background = rounded(Colors.AppleBlue, 999)
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
        val timeCurrent = label("0:00", 14f, Colors.TextSecondary, bold = true, maxLines = 1)
        val timeTotal = label("0:00", 14f, Colors.TextTertiary, maxLines = 1)
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
            Colors.TextTertiary,
            maxLines = 1,
        ).apply { gravity = Gravity.CENTER }
        bottom.addView(hint, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(16)))

        overlay.addView(bottom, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

        fun updateProgress() {
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
                updateProgress()
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
                player.playbackState == Player.STATE_BUFFERING -> Colors.TextTertiary
                isPlaying -> Colors.AppleBlue
                else -> Colors.TextSecondary
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
                player.removeListener(listener)
                overlay.removeCallbacks(progressTick)
            }
        })
        return overlay
    }

    private fun showDescriptionDialog(title: String, description: String) {
        Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SurfaceElevated, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label(title, 24f, Colors.TextPrimary, bold = true, maxLines = 2).apply {
                    letterSpacingCompat(-0.01f)
                },
                linearMatch(),
            )
            val scroll = ScrollView(this@MainActivity)
            scroll.addView(
                label(description, 17f, Colors.TextSecondary, maxLines = Int.MAX_VALUE).apply {
                    setLineSpacing(dp(6).toFloat(), 1f)
                },
                frameMatch(),
            )
            card.addView(scroll, LinearLayout.LayoutParams(match(), 0, 1f).withTopMargin(dp(12)))
            val close = pill("关闭", selected = true, heightDp = 48, horizontalPaddingDp = 28) { dismiss() }.apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scroll.smoothScrollBy(0, dp(180))
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            scroll.smoothScrollBy(0, -dp(180))
                            true
                        }
                        else -> false
                    }
                }
            }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
            close.post { close.requestFocus() }
        }.show()
    }

    private fun showAllPagesDialog(pages: PlaybackPages, bvid: String) {
        Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SurfaceElevated, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("选集 · 共${pages.totalPages}集", 22f, Colors.TextPrimary, bold = true, maxLines = 1),
                linearMatch(),
            )
            val scroll = ScrollView(this@MainActivity)
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            for (i in 0 until pages.totalPages) {
                val title = pages.pageTitles.getOrElse(i) { "第${i + 1}集" }
                val isCurrent = i == pages.currentPage
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        dismiss()
                        viewModel.selectPage(i)
                    }
                    background = rounded(if (isCurrent) ColorUtils.setAlphaComponent(Colors.AppleBlue, 38) else Color.TRANSPARENT, 12, 0, Color.TRANSPARENT)
                    val idxLabel = label("${i + 1}", 13f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
                        gravity = Gravity.CENTER
                        minWidth = dp(36)
                    }
                    addView(idxLabel, LinearLayout.LayoutParams(dp(36), wrap()))
                    val titleLabel = label(title, 15f, if (isCurrent) Colors.AppleBlue else Colors.TextPrimary, bold = isCurrent, maxLines = 1).apply {
                        layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).withStartMargin(dp(8))
                    }
                    addView(titleLabel)
                }
                applyCardFocus(row)
                list.addView(row, linearMatch())
            }
            scroll.addView(list, frameMatch())
            card.addView(scroll, LinearLayout.LayoutParams(match(), 0, 1f).withTopMargin(dp(12)))
            val close = pill("关闭", selected = true, heightDp = 48, horizontalPaddingDp = 28) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
            close.post { close.requestFocus() }
        }.show()
    }

    private fun promptInstallUpdate(result: AppUpdateResult.ReadyToInstall) {
        pendingUpdateApk = result.apk
        Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SurfaceElevated, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("发现新版本 ${result.update.versionName}", 22f, Colors.TextPrimary, bold = true, maxLines = 2),
                linearMatch(),
            )
            val notes = result.update.releaseNotes.ifBlank { "已下载更新包，安装后可继续使用最新版本。" }
            card.addView(
                label(notes, 16f, Colors.TextSecondary, maxLines = 5).apply {
                    setLineSpacing(dp(4).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(12)),
            )
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val install = primaryPill("安装更新", heightDp = 48, horizontalPaddingDp = 28) {
                dismiss()
                handleInstallLaunch(appUpdateManager.launchInstaller(result.apk), result.apk)
            }
            val later = pill("稍后", selected = false, heightDp = 48, horizontalPaddingDp = 24) {
                pendingUpdateApk = null
                dismiss()
            }
            actions.addView(install, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(12)))
            actions.addView(later, LinearLayout.LayoutParams(wrap(), dp(48)))
            card.addView(actions, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(18)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.48f).toInt(), wrap())
            install.post { install.requestFocus() }
        }.show()
    }

    private fun handleInstallLaunch(result: InstallLaunchResult, apk: File) {
        when (result) {
            InstallLaunchResult.Started -> {
                pendingUpdateApk = null
            }
            InstallLaunchResult.NeedsPermission -> {
                pendingUpdateApk = apk
                showUpdatePermissionDialog()
            }
            is InstallLaunchResult.Failed -> showUpdateMessage(result.message)
        }
    }

    private fun showUpdatePermissionDialog() {
        Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SurfaceElevated, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("需要允许安装未知应用", 22f, Colors.TextPrimary, bold = true, maxLines = 2),
                linearMatch(),
            )
            card.addView(
                label("请在系统设置中允许本应用安装 APK，返回后会自动继续安装。", 16f, Colors.TextSecondary, maxLines = 3),
                LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(12)),
            )
            val open = primaryPill("打开设置", heightDp = 48, horizontalPaddingDp = 28) {
                dismiss()
                runCatching { appUpdateManager.launchInstallPermissionSettings() }
                    .onFailure { showUpdateMessage("无法打开安装权限设置，请在系统设置中手动允许") }
            }
            card.addView(open, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(18)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.48f).toInt(), wrap())
            open.post { open.requestFocus() }
        }.show()
    }

    private fun showUpdateMessage(message: String) {
        Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                background = rounded(Colors.SurfaceElevated, 22, 1, Colors.GlassBorder)
            }
            card.addView(label(message, 17f, Colors.TextSecondary, maxLines = 3), linearMatch())
            val close = pill("知道了", selected = true, heightDp = 44, horizontalPaddingDp = 24) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(44)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.42f).toInt(), wrap())
            close.post { close.requestFocus() }
        }.show()
    }

    private fun setContent(view: View) {
        root.removeAllViews()
        playerRetryOverlay = null
        playerErrorOverlay = null
        root.addView(view, frameMatch())
    }

    // 自动恢复进行中(loading 且仍在播放页)时,在播放画面上盖一层“正在重试…”提示;
    // 恢复结束(重建播放器或退出播放页)后移除。setContent 会清空 root,因此一并清引用。
    private fun updatePlayerRetryOverlay(show: Boolean) {
        if (show) {
            if (playerRetryOverlay == null) {
                val scrim = FrameLayout(this).apply {
                    setBackgroundColor(Color.argb(140, 0, 0, 0))
                    isClickable = false
                    addView(
                        centeredLoading("正在重试…"),
                        FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER),
                    )
                }
                playerRetryOverlay = scrim
                root.addView(scrim, frameMatch())
            }
        } else {
            playerRetryOverlay?.let { root.removeView(it) }
            playerRetryOverlay = null
        }
    }

    // 播放失败且没有 details 信息可退(fallbackSelected 为 null)时,在播放画面上盖一层
    // 错误提示 + 重试按钮;避免把用户直接踢到主页。用户按 BACK 退出或点"重试"重建播放器。
    private fun updatePlayerErrorOverlay(error: String?) {
        if (error == null) {
            playerErrorOverlay?.let { root.removeView(it) }
            playerErrorOverlay = null
            return
        }
        if (playerErrorOverlay != null) return
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(40), dp(44), dp(36))
            background = roundedGradient(Colors.SurfaceFocused, Colors.SurfaceElevated, 30, 1, Colors.GlassBorder)
            minimumWidth = dp(360)
            elevationCompat(dp(18).toFloat())
        }
        val iconWrap = FrameLayout(this).apply {
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
        }
        iconWrap.addView(WarningGlyphView(this), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(iconWrap, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(
            label("播放失败", 20f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(16)),
        )
        card.addView(
            label(error, 15f, Colors.TextSecondary, maxLines = 3).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(320)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(8)),
        )
        val retry = primaryPill("重试", heightDp = 50, horizontalPaddingDp = 40) {
            viewModel.dismissPlaybackError()
        }
        card.addView(retry, LinearLayout.LayoutParams(wrap(), dp(50)).withTopMargin(dp(24)))
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true
            addView(card, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }
        playerErrorOverlay = scrim
        root.addView(scrim, frameMatch())
        retry.post { retry.requestFocus() }
    }

    private fun releasePlayer() {
        cancelPlaybackStabilityReset()
        player?.release()
        player = null
        currentPlayerUrl = null
        playerSurface = null
        orientationIndicator = null
    }

    private fun applyPlayerVideoRotation() {
        val surface = playerSurface
        val parent = surface?.parent as? FrameLayout
        if (surface != null && parent != null) {
            val parentWidth = parent.width
            val parentHeight = parent.height
            if (parentWidth > 0 && parentHeight > 0) {
                val swapSize = playerVideoRotationDegrees % 180 != 0
                val targetWidth = if (swapSize) parentHeight else parentWidth
                val targetHeight = if (swapSize) parentWidth else parentHeight
                val params = (surface.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
                if (params.width != targetWidth ||
                    params.height != targetHeight ||
                    params.gravity != Gravity.CENTER
                ) {
                    params.width = targetWidth
                    params.height = targetHeight
                    params.gravity = Gravity.CENTER
                    surface.layoutParams = params
                }
                surface.rotation = playerVideoRotationDegrees.toFloat()
            }
        }
        updateOrientationIndicator()
    }

    // 用户用长按 OK / MENU 在旋转角度间切换;标记为手动选择,后续不再被自动检测覆盖。
    private fun togglePlayerOrientation() {
        playerVideoRotationDegrees = nextManualVideoRotation(playerVideoRotationDegrees)
        playerVideoRotationUserSet = true
        AppLog.i("PlayerKey", "togglePlayerOrientation -> rotation=$playerVideoRotationDegrees")
        applyPlayerVideoRotation()
    }

    // 根据视频真实分辨率自动判定:竖向编码的视频在 TV 上默认旋转 90 度横看;
    // 若流本身带有未应用的旋转元数据,优先使用元数据角度。用户手动选择过则不覆盖。
    private fun autoDetectPlayerVideoRotation(videoSize: VideoSize) {
        if (playerVideoRotationUserSet) return
        val metadataRotation = normalizeVideoRotation(videoSize.unappliedRotationDegrees)
        val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val detectedRotation = if (metadataRotation != 0) {
            metadataRotation
        } else if (videoSize.height > displayWidth) {
            90
        } else {
            0
        }
        if (detectedRotation != playerVideoRotationDegrees) {
            playerVideoRotationDegrees = detectedRotation
            applyPlayerVideoRotation()
        } else {
            updateOrientationIndicator()
        }
    }

    private fun updateOrientationIndicator() {
        orientationIndicator?.setTextIfChanged("画面 · ${videoRotationLabel()}")
    }

    private fun nextManualVideoRotation(currentDegrees: Int): Int {
        return when (normalizeVideoRotation(currentDegrees)) {
            0 -> 90
            90 -> 270
            270 -> 180
            else -> 0
        }
    }

    private fun normalizeVideoRotation(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            90, 180, 270 -> normalized
            else -> 0
        }
    }

    private fun videoRotationLabel(): String {
        return when (normalizeVideoRotation(playerVideoRotationDegrees)) {
            90 -> "右转 90°"
            180 -> "旋转 180°"
            270 -> "左转 90°"
            else -> "原始"
        }
    }

    // 离开播放页时清空播放页专用的旋转 UI 引用;浏览/详情始终维持 TV 默认横屏布局。
    private fun resetPlayerOrientationToDefault() {
        orientationIndicator = null
    }

    // STATE_READY 后启动稳定计时:延迟 [PlaybackStabilityResetMs] 后,若仍在顺畅播放
    // 同一条流,则通知 ViewModel 这条流确实可用、重置恢复额度。任何失败/结束/缓冲
    // 或重建播放器都会取消该计时,从而不会被“就绪即失败”的流误判为稳定。
    private fun schedulePlaybackStabilityReset(streamId: String) {
        cancelPlaybackStabilityReset()
        val runnable = Runnable {
            playbackStabilityRunnable = null
            val activePlayer = player
            if (activePlayer != null &&
                activePlayer.playbackState == Player.STATE_READY &&
                activePlayer.playWhenReady
            ) {
                AppLog.i("Player", "stream $streamId stable for ${PlaybackStabilityResetMs}ms")
                viewModel.playbackSustained(streamId)
            }
        }
        playbackStabilityRunnable = runnable
        root.postDelayed(runnable, PlaybackStabilityResetMs)
    }

    private fun cancelPlaybackStabilityReset() {
        playbackStabilityRunnable?.let { root.removeCallbacks(it) }
        playbackStabilityRunnable = null
    }

    private fun exitSearchMode() {
        searchVisible = false
        currentState.categories.firstOrNull()?.let {
            pendingHomeFocus = HomeFocusTarget.Category(it.id)
            rememberedCategoryId = it.id
            homeFocusArea = HomeFocusArea.Category
            viewModel.selectCategory(it)
        }
    }

    private fun requestHomeFocus(target: HomeFocusTarget): Boolean {
        pendingHomeFocus = target
        if (target is HomeFocusTarget.Video) {
            val adapter = homeVideoAdapter
            val recycler = homeVideoRecycler
            val index = adapter?.indexOfVideo(target.id) ?: -1
            if (recycler != null && adapter != null && index >= 0) {
                adapter.requestFocusOn(target)
                recycler.scrollToPosition(index)
                recycler.post {
                    root.findViewWithTag<View>(target.viewTag())?.let { view ->
                        if (view.requestFocus()) pendingHomeFocus = null
                    }
                }
                return true
            }
        }
        val view = root.findViewWithTag<View>(target.viewTag())
        if (view != null) {
            view.requestFocus()
            pendingHomeFocus = null
            return true
        }
        invalidateUi()
        return true
    }

    private fun homeFocusTargetFor(state: MediaUiState): HomeFocusTarget? {
        pendingHomeFocus?.let { return it }
        return when {
            state.query.isNotBlank() && homeFocusArea == HomeFocusArea.Video ->
                state.items.firstOrNull()?.id?.let(HomeFocusTarget::Video)
            homeFocusArea == HomeFocusArea.Video -> {
                val rememberedVideoId = rememberedVideoFocusByCategory[homeCategoryKey(state)]
                rememberedVideoId
                    ?.takeIf { id -> state.items.any { it.id == id } }
                    ?.let(HomeFocusTarget::Video)
                    ?: state.items.firstOrNull()?.id?.let(HomeFocusTarget::Video)
            }
            homeFocusArea == HomeFocusArea.Search -> HomeFocusTarget.Search
            else -> homeDefaultCategoryTarget(state)
        }
    }

    private fun homeDefaultCategoryTarget(state: MediaUiState): HomeFocusTarget? {
        val categoryId = rememberedCategoryId
            ?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.selectedCategoryId?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.categories.firstOrNull()?.id
        return categoryId?.let(HomeFocusTarget::Category)
    }

    private fun homeCategoryKey(state: MediaUiState): String {
        return state.selectedCategoryId ?: HomeFocusNoCategoryKey
    }

    private fun centeredLoading(text: String): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = rounded(Colors.GlassFillStrong, 28, 1, Colors.GlassBorder)
        }
        card.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(label(text, 16f, Colors.TextSecondary, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(14)))
        return centeredCard(card)
    }

    private fun loadingScrim(): View {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = rounded(Color.argb(184, 0, 0, 0), 999, 1, Colors.GlassBorder)
        }
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        box.addView(label("正在加载…", 15f, Colors.TextPrimary, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(12)))
        return box
    }

    private fun errorState(message: String, onRetry: () -> Unit): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(40), dp(44), dp(36))
            background = roundedGradient(Colors.SurfaceFocused, Colors.SurfaceElevated, 30, 1, Colors.GlassBorder)
            minimumWidth = dp(360)
            elevationCompat(dp(18).toFloat())
        }
        val iconWrap = FrameLayout(this).apply {
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
        }
        iconWrap.addView(WarningGlyphView(this), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(iconWrap, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(
            label("加载失败", 20f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(16)),
        )
        card.addView(
            label(message, 15f, Colors.TextSecondary, maxLines = 3).apply {
                gravity = Gravity.CENTER
                maxWidth = dp(320)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(8)),
        )
        val retry = primaryPill("重试", heightDp = 50, horizontalPaddingDp = 40, onClick = onRetry)
        card.addView(retry, LinearLayout.LayoutParams(wrap(), dp(50)).withTopMargin(dp(24)))
        retry.post { retry.requestFocus() }
        return centeredCard(card)
    }

    private fun errorInline(message: String, onRetry: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(label(message, 14f, Colors.TextDanger, maxLines = 2), LinearLayout.LayoutParams(0, wrap(), 1f))
        row.addView(pill("重试", selected = false, heightDp = 38, horizontalPaddingDp = 18, onClick = onRetry), LinearLayout.LayoutParams(wrap(), dp(38)).withStartMargin(dp(12)))
        return row
    }

    private fun homeInlineError(message: String): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(Color.argb(210, 14, 16, 20), 22, 1, Colors.GlassBorder)
            addView(label(message, 14f, Colors.TextPrimary, bold = true, maxLines = 2), LinearLayout.LayoutParams(match(), wrap()))
        }
    }

    private fun emptyState(message: String): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(26), dp(34), dp(26))
            background = rounded(Colors.GlassFill, 28, 1, Colors.GlassBorder)
        }
        card.addView(label(message, 18f, Colors.TextTertiary, bold = true, maxLines = 1), linearWrap())
        card.addView(label("换个分类或关键词试试", 13f, Colors.TextTertiary, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(8)))
        return centeredCard(card)
    }

    private fun centeredCard(card: View): FrameLayout {
        return FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int,
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            // 字号同样按 TV 设计密度换算成像素,与 dp() 布局保持同一比例基准,
            // 避免设备上报 density 异常时文字与布局比例不一致。
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeSp * tvDensity)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setIncludeFontPadding(true)
            setMaxLines(maxLines)
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun glassText(
        text: String,
        sizeSp: Float,
        color: Int,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): TextView {
        return label(text, sizeSp, color, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = true
            isClickable = true
            applyPillFocus(this, false, onFocusChange)
        }
    }

    private fun iconPill(
        text: String,
        icon: PillIcon,
        selected: Boolean,
        heightDp: Int,
        horizontalPaddingDp: Int,
        onClick: () -> Unit,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = PillIconView(this, icon).apply {
            setIconColor(Colors.TextPrimary)
        }
        val textView = label(text, 15f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
        row.addView(textView, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(10)))
        applyPillFocus(row, selected, onFocusChange) { color ->
            iconView.setIconColor(color)
            textView.setTextColor(color)
        }
        return row
    }

    private fun circleIconButton(
        icon: PillIcon,
        onClick: () -> Unit,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ): FrameLayout {
        val button = FrameLayout(this).apply {
            minimumWidth = dp(54)
            minimumHeight = dp(54)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = PillIconView(this, icon).apply {
            setIconColor(Colors.TextPrimary)
        }
        button.addView(iconView, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        fun update(focused: Boolean) {
            button.background = rounded(
                if (focused) Colors.AppleBlue else Colors.GlassFill,
                999,
                if (focused) 3 else 1,
                if (focused) Colors.GlassBorderFocused else Colors.GlassBorder,
            )
            button.elevationCompat(if (focused) dp(8).toFloat() else 0f)
            button.animate().cancel()
            button.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(140).start()
        }
        update(false)
        button.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
        return button
    }

    private fun primaryPill(
        text: String,
        heightDp: Int,
        horizontalPaddingDp: Int,
        textSizeSp: Float = 16f,
        icon: PillIcon? = null,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iconView = icon?.let {
            PillIconView(this, it).apply { setIconColor(Colors.DeepSpace) }
        }
        if (iconView != null) {
            row.addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        val textView = label(text, textSizeSp, Colors.DeepSpace, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        row.addView(
            textView,
            LinearLayout.LayoutParams(wrap(), wrap()).apply {
                if (iconView != null) leftMargin = dp(10)
            },
        )
        fun update(focused: Boolean) {
            row.background = rounded(
                if (focused) Colors.AppleBlue else Colors.TextPrimary,
                999,
                if (focused) 3 else 0,
                if (focused) Colors.GlassBorderFocused else Color.TRANSPARENT,
            )
            row.elevationCompat(if (focused) dp(10).toFloat() else dp(2).toFloat())
            val contentColor = if (focused) Colors.TextPrimary else Colors.DeepSpace
            textView.setTextColor(contentColor)
            iconView?.setIconColor(contentColor)
            row.animate().cancel()
            row.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(140).start()
        }
        update(false)
        row.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
        return row
    }

    private fun pill(
        text: String,
        selected: Boolean,
        heightDp: Int,
        horizontalPaddingDp: Int,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit,
    ): TextView {
        return label(text, 15f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            setPadding(dp(horizontalPaddingDp), 0, dp(horizontalPaddingDp), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            applyPillFocus(this, selected, onFocusChange)
        }
    }

    private fun applyPillFocus(
        view: TextView,
        selected: Boolean,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ) {
        applyPillFocus(view, selected, onFocusChange) { color ->
            view.setTextColor(color)
        }
    }

    private fun applyPillFocus(
        view: View,
        selected: Boolean,
        onFocusChange: ((Boolean) -> Unit)? = null,
        setContentColor: (Int) -> Unit,
    ) {
        fun update(focused: Boolean) {
            view.background = when {
                focused -> rounded(Colors.AppleBlue, 999, 3, Colors.GlassBorderFocused)
                selected -> rounded(Colors.GlassFillStrong, 999, 2, Colors.AppleBlue)
                else -> rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            }
            view.elevationCompat(if (focused) dp(8).toFloat() else 0f)
            setContentColor(
                when {
                    focused -> Colors.TextPrimary
                    selected -> Colors.AppleBlue
                    else -> Colors.TextSecondary
                },
            )
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
        }
        update(false)
        view.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
    }

    private fun applyCardFocus(
        view: View,
        onFocusChange: ((Boolean) -> Unit)? = null,
    ) {
        fun update(focused: Boolean) {
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
        }
        update(false)
        view.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
    }

    private fun loadImage(
        url: String?,
        headers: Map<String, String>,
        image: ThumbnailImageView,
        placeholder: View,
        title: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ) {
        val imageUrl = url?.takeIf { it.isNotBlank() }
        image.cancelThumbnailLoad()
        image.tag = imageUrl
        image.setThumbnailBitmap(null)
        setPlaceholderVisible(placeholder, true)
        if (imageUrl == null) return
        ImageMemoryCache.get(imageUrl)?.let {
            image.setThumbnailBitmap(it)
            setPlaceholderVisible(placeholder, false)
            return
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                image.cancelThumbnailLoad()
            }
        }
        val job = lifecycleScope.launch {
            try {
                val bitmap = ThumbnailFetchSemaphore.withPermit {
                    fetchBitmap(
                        url = imageUrl,
                        headers = headers,
                        maxWidthPx = maxWidthPx.coerceAtLeast(dp(160)),
                        maxHeightPx = maxHeightPx.coerceAtLeast(dp(120)),
                    )
                }
                if (bitmap != null) {
                    ImageMemoryCache.put(imageUrl, bitmap)
                } else {
                    Log.w("MediaCenterImage", "Unable to load thumbnail for $title")
                }
                if (image.tag == imageUrl && bitmap != null) {
                    image.setThumbnailBitmap(bitmap)
                    setPlaceholderVisible(placeholder, false)
                }
            } finally {
                image.clearThumbnailLoad(job = coroutineContext[Job])
            }
        }
        image.trackThumbnailLoad(job, detachListener)
    }

    // 详情页模糊背景:复用缩略图字节,降采样到极小尺寸后由 ImageView 双线性放大近似模糊
    // (minSdk=19,无法用 RenderEffect)。背景位图单独按 "backdrop:" key 缓存,只在内存渲染。
    private fun loadBackdropImage(
        url: String?,
        headers: Map<String, String>,
        target: ThumbnailImageView,
    ) {
        val imageUrl = url?.takeIf { it.isNotBlank() } ?: return
        val cacheKey = "backdrop:$imageUrl"
        target.tag = cacheKey
        ImageMemoryCache.get(cacheKey)?.let {
            target.setThumbnailBitmap(it)
            target.animate().alpha(BackdropAlpha).setDuration(420).start()
            return
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                target.cancelThumbnailLoad()
            }
        }
        val job = lifecycleScope.launch {
            try {
                val bitmap = ThumbnailFetchSemaphore.withPermit {
                    val bytes = fetchThumbnailBytesWithRetry(imageUrl, headers)
                    withContext(Dispatchers.Default) {
                        // 固定极小目标像素(不随密度),放大后形成柔和模糊。
                        decodeSampledBitmap(bytes, 48, 48)
                    }
                }
                if (bitmap != null) {
                    ImageMemoryCache.put(cacheKey, bitmap)
                    if (target.tag == cacheKey) {
                        target.setThumbnailBitmap(bitmap)
                        target.animate().alpha(BackdropAlpha).setDuration(420).start()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                Log.w("MediaCenterImage", "Backdrop request failed", error)
            } catch (error: RuntimeException) {
                Log.w("MediaCenterImage", "Backdrop request failed", error)
            } finally {
                target.clearThumbnailLoad(job = coroutineContext[Job])
            }
        }
        target.trackThumbnailLoad(job, detachListener)
    }

    private fun setPlaceholderVisible(placeholder: View, visible: Boolean) {
        placeholder.animate().cancel()
        placeholder.visibility = View.VISIBLE
        placeholder.alpha = if (visible) 1f else 0f
    }

    private suspend fun fetchBitmap(
        url: String,
        headers: Map<String, String>,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap? {
        return try {
            val bytes = fetchThumbnailBytesWithRetry(url, headers)
            withContext(Dispatchers.Default) {
                decodeSampledBitmap(bytes, maxWidthPx, maxHeightPx)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.w("MediaCenterImage", "Thumbnail request failed", error)
            null
        } catch (error: RuntimeException) {
            Log.w("MediaCenterImage", "Thumbnail request failed", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.e("MediaCenterImage", "Thumbnail decode exceeded memory budget", error)
            null
        }
    }

    // asset 代理回源偶发瞬时 5xx(典型为 Cloudflare error code: 502),对瞬时失败做
    // 有限指数退避重试,缓存命中后通常即可恢复,避免直接降级为占位图。
    private suspend fun fetchThumbnailBytesWithRetry(
        url: String,
        headers: Map<String, String>,
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return fetchThumbnailBytes(url, headers)
            } catch (error: ThumbnailHttpException) {
                if (!error.isTransient || attempt >= MaxThumbnailRetries) throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= MaxThumbnailRetries) throw error
            }
            delay(ThumbnailRetryBackoffMs[attempt])
            attempt++
        }
    }

    private suspend fun fetchThumbnailBytes(
        url: String,
        headers: Map<String, String>,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        if (!headers.containsHeader("Accept")) {
            requestBuilder.header("Accept", "image/*,*/*;q=0.8")
        }
        if (!headers.containsHeader("User-Agent")) {
            requestBuilder.header("User-Agent", PlaybackUserAgent)
        }
        headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()
        val call = ThumbnailHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.readBoundedThumbnailBytes()
                    if (continuation.isActive) {
                        continuation.resume(bytes)
                    }
                } catch (error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                } catch (error: RuntimeException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun Response.readBoundedThumbnailBytes(): ByteArray {
        return use { response ->
            if (!response.isSuccessful) {
                throw ThumbnailHttpException(response.code())
            }
            val body = response.body() ?: throw IOException("Thumbnail response body is empty")
            val contentLength = body.contentLength()
            if (contentLength > MaxThumbnailBytes) {
                throw IOException("Thumbnail exceeds $MaxThumbnailBytes bytes")
            }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalBytes = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > MaxThumbnailBytes) {
                        throw IOException("Thumbnail exceeds $MaxThumbnailBytes bytes")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = bitmapSampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun bitmapSampleSize(width: Int, height: Int, maxWidthPx: Int, maxHeightPx: Int): Int {
        var sampleSize = 1
        val targetWidth = maxWidthPx.coerceAtLeast(1)
        val targetHeight = maxHeightPx.coerceAtLeast(1)
        while (height / (sampleSize * 2) >= targetHeight && width / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun appBackground(): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Colors.BackgroundTop, Colors.BackgroundMid, Colors.DeepSpace),
        )
        // 顶部中央叠一层极淡的 Apple-Blue 径向柔光,增加纵深而不引入新色相。
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Colors.AccentGlow, Color.TRANSPARENT),
        ).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(560).toFloat()
            setGradientCenter(0.5f, 0f)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    private fun detailBackdropScrim(): View {
        val scrim = FrameLayout(this)
        scrim.addView(
            View(this).apply { setBackgroundColor(Color.argb(118, 5, 7, 13)) },
            frameMatch(),
        )
        scrim.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.TRANSPARENT, Color.argb(150, 5, 7, 13)),
                )
            },
            frameMatch(),
        )
        scrim.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(232, 0, 0, 0), Color.TRANSPARENT),
                )
            },
            frameMatch(),
        )
        return scrim
    }

    private fun rounded(
        color: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun roundedGradient(
        topColor: Int,
        bottomColor: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor),
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun View.elevationCompat(value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = value
        }
    }

    private fun TextView.letterSpacingCompat(value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            letterSpacing = value
        }
    }

    private fun TextView.setTextIfChanged(value: String) {
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

    private fun videoMeta(video: VideoSummary): String {
        return listOfNotNull(
            video.durationSeconds?.let { formatDurationSeconds(it) },
        ).joinToString(" · ").ifBlank { "公开视频" }
    }

    private fun formatDurationSeconds(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val sec = total % 60
        val min = (total / 60) % 60
        val hr = total / 3600
        return if (hr > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hr, min, sec)
        } else {
            String.format(Locale.ROOT, "%d:%02d", min, sec)
        }
    }

    private fun formatTime(ms: Long): String {
        return formatDurationSeconds(ms / 1000)
    }

    private fun gridColumns(): Int {
        val width = resources.displayMetrics.widthPixels
        val available = width - dp(96)
        return max(1, (available + dp(16)) / (dp(116) + dp(16)))
    }

    private fun posterCardWidth(columns: Int): Int {
        val available = resources.displayMetrics.widthPixels - dp(96) - dp(16 * columns)
        return max(dp(116), available / columns)
    }

    private fun match(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun frameMatch(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(match(), match())
    }

    private fun linearMatch(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(match(), wrap())
    }

    private fun linearWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(wrap(), wrap())
    }

    private fun LinearLayout.LayoutParams.withStartMargin(value: Int): LinearLayout.LayoutParams {
        leftMargin = value
        return this
    }

    private fun LinearLayout.LayoutParams.withEndMargin(value: Int): LinearLayout.LayoutParams {
        rightMargin = value
        return this
    }

    private fun LinearLayout.LayoutParams.withTopMargin(value: Int): LinearLayout.LayoutParams {
        topMargin = value
        return this
    }

    // TV 设计密度:以 Android TV 标准设计画布 960×540dp(1080p=xhdpi)为基准,
    // 用真实像素尺寸反推有效密度,使布局尺寸与字号只取决于真实分辨率比例,
    // 不受设备上报 density 影响。在正确上报的 1080p 设备上结果等于系统 density(2.0),
    // 因此对规范设备零副作用;在误报 density 的电视/盒子上则自动校正,
    // 让全部页面在 720p/1080p/4K 上保持一致的 10ft 视觉比例与可读性。
    private val tvDensity: Float
        get() {
            val metrics = resources.displayMetrics
            val byHeight = metrics.heightPixels / 540f
            val byWidth = metrics.widthPixels / 960f
            val derived = minOf(byHeight, byWidth)
            return if (derived.isFinite() && derived > 0f) derived else metrics.density
        }

    private fun dp(value: Int): Int {
        return (value * tvDensity + 0.5f).toInt()
    }
}

private object Colors {
    const val DeepSpace: Int = 0xFF000000.toInt()
    const val BackgroundTop: Int = 0xFF15182B.toInt()
    const val BackgroundMid: Int = 0xFF0A0B12.toInt()
    const val SurfaceElevated: Int = 0xFF0E1014.toInt()
    const val SurfaceFocused: Int = 0xFF181C26.toInt()
    const val GlassBorderFocused: Int = 0xFFE5E5E7.toInt()
    const val TextPrimary: Int = 0xFFF5F5F7.toInt()
    const val TextSecondary: Int = 0xFFB0B3B8.toInt()
    const val TextTertiary: Int = 0xFF86868B.toInt()
    const val TextDanger: Int = 0xFFFF453A.toInt()
    const val AppleBlue: Int = 0xFF0A84FF.toInt()
    val AccentGlow: Int = Color.argb(48, 10, 132, 255)
    val GlassFill: Int = Color.argb(20, 255, 255, 255)
    val GlassFillStrong: Int = Color.argb(31, 255, 255, 255)
    val GlassBorder: Int = Color.argb(32, 255, 255, 255)
    val PlaceholderTop: Int = Color.argb(255, 28, 32, 44)
    val PlaceholderBottom: Int = Color.argb(255, 12, 14, 20)
}

private object ImageMemoryCache {
    // 按应用可用堆的 1/8 估算缓存上限(Android 推荐做法),再夹到 8~24MB。
    // 低堆的 API19 电视盒子会自动收缩,避免缩略图缓存撑爆内存导致 OOM。
    private val maxSizeKb: Int = ((Runtime.getRuntime().maxMemory() / 1024) / 8)
        .coerceIn(8L * 1024, 24L * 1024)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }
}

private enum class HomeFocusArea {
    Search,
    Category,
    Video,
}

private sealed class HomeFocusTarget {
    object Search : HomeFocusTarget()
    data class Category(val id: String) : HomeFocusTarget()
    data class Video(val id: String) : HomeFocusTarget()

    fun viewTag(): String {
        return when (this) {
            Search -> "home:search"
            is Category -> "home:category:$id"
            is Video -> "home:video:$id"
        }
    }
}

private sealed class DetailFocusTarget {
    object Play : DetailFocusTarget()
    object Refresh : DetailFocusTarget()
    data class Option(val id: String) : DetailFocusTarget()
    data class Page(val index: Int) : DetailFocusTarget()
}

private const val HomeFocusNoCategoryKey = "__no_category__"

private val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)

private enum class PillIcon {
    Search,
    Close,
    Play,
}

private class ThumbnailImageView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private var bitmap: Bitmap? = null
    private var loadJob: Job? = null
    private var loadDetachListener: OnAttachStateChangeListener? = null

    fun setThumbnailBitmap(value: Bitmap?) {
        if (bitmap === value) return
        bitmap = value
        invalidate()
    }

    fun trackThumbnailLoad(job: Job, detachListener: OnAttachStateChangeListener) {
        cancelThumbnailLoad()
        loadJob = job
        loadDetachListener = detachListener
        addOnAttachStateChangeListener(detachListener)
    }

    fun clearThumbnailLoad(job: Job?) {
        if (job == null || loadJob !== job) return
        loadJob = null
        loadDetachListener?.let(::removeOnAttachStateChangeListener)
        loadDetachListener = null
    }

    fun cancelThumbnailLoad() {
        loadJob?.cancel()
        loadJob = null
        loadDetachListener?.let(::removeOnAttachStateChangeListener)
        loadDetachListener = null
    }

    override fun onDetachedFromWindow() {
        cancelThumbnailLoad()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = bitmap ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0 || current.width <= 0 || current.height <= 0) return

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val bitmapRatio = current.width.toFloat() / current.height.toFloat()
        if (bitmapRatio > viewRatio) {
            val cropWidth = (current.height * viewRatio).toInt().coerceAtLeast(1)
            val left = (current.width - cropWidth) / 2
            src.set(left, 0, left + cropWidth, current.height)
        } else {
            val cropHeight = (current.width / viewRatio).toInt().coerceAtLeast(1)
            val top = (current.height - cropHeight) / 2
            src.set(0, top, current.width, top + cropHeight)
        }
        dst.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        canvas.drawBitmap(current, src, dst, paint)
    }
}

private class PillIconView(
    context: Context,
    private val icon: PillIcon,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var iconColor: Int = Colors.TextPrimary

    fun setIconColor(color: Int) {
        if (iconColor == color) return
        iconColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = (w.coerceAtMost(h) * 0.12f).coerceAtLeast(2f)
        paint.strokeWidth = stroke
        when (icon) {
            PillIcon.Search -> {
                val radius = w.coerceAtMost(h) * 0.28f
                val cx = w * 0.43f
                val cy = h * 0.43f
                canvas.drawCircle(cx, cy, radius, paint)
                canvas.drawLine(
                    cx + radius * 0.72f,
                    cy + radius * 0.72f,
                    w * 0.88f,
                    h * 0.88f,
                    paint,
                )
            }
            PillIcon.Close -> {
                canvas.drawLine(w * 0.26f, h * 0.26f, w * 0.74f, h * 0.74f, paint)
                canvas.drawLine(w * 0.74f, h * 0.26f, w * 0.26f, h * 0.74f, paint)
            }
            PillIcon.Play -> {
                fillPaint.color = iconColor
                path.reset()
                path.moveTo(w * 0.28f, h * 0.18f)
                path.lineTo(w * 0.28f, h * 0.82f)
                path.lineTo(w * 0.82f, h * 0.50f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
        }
    }
}

// 播放页传输控制图标枚举:配合 Canvas 自绘图标使用(不用 Unicode 符号)。
private enum class TransportIcon {
    Previous,
    Rewind,
    Forward,
    Next,
    Aspect,
}

// 播放页传输控制图标:统一用 Canvas 绘制(不用 Unicode 符号),与全局图标风格一致。
private class TransportIconView(
    context: Context,
    private val icon: TransportIcon,
) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val bar = RectF()
    private var iconColor: Int = Colors.TextPrimary

    fun setIconColor(color: Int) {
        if (iconColor == color) return
        iconColor = color
        invalidate()
    }

    private fun Canvas.triangleRight(left: Float, apexX: Float, top: Float, bottom: Float) {
        path.reset()
        path.moveTo(left, top)
        path.lineTo(left, bottom)
        path.lineTo(apexX, (top + bottom) / 2f)
        path.close()
        drawPath(path, fillPaint)
    }

    private fun Canvas.triangleLeft(right: Float, apexX: Float, top: Float, bottom: Float) {
        path.reset()
        path.moveTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(apexX, (top + bottom) / 2f)
        path.close()
        drawPath(path, fillPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fillPaint.color = iconColor
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * 0.20f
        val bottom = h * 0.80f
        val barRadius = w * 0.06f
        when (icon) {
            TransportIcon.Forward -> {
                canvas.triangleRight(w * 0.10f, w * 0.50f, top, bottom)
                canvas.triangleRight(w * 0.50f, w * 0.90f, top, bottom)
            }
            TransportIcon.Rewind -> {
                canvas.triangleLeft(w * 0.90f, w * 0.50f, top, bottom)
                canvas.triangleLeft(w * 0.50f, w * 0.10f, top, bottom)
            }
            TransportIcon.Next -> {
                canvas.triangleRight(w * 0.14f, w * 0.62f, top, bottom)
                bar.set(w * 0.74f, top, w * 0.86f, bottom)
                canvas.drawRoundRect(bar, barRadius, barRadius, fillPaint)
            }
            TransportIcon.Previous -> {
                canvas.triangleLeft(w * 0.86f, w * 0.38f, top, bottom)
                bar.set(w * 0.14f, top, w * 0.26f, bottom)
                canvas.drawRoundRect(bar, barRadius, barRadius, fillPaint)
            }
            TransportIcon.Aspect -> {
                // 四角括号构成的画面边框图标,表示“画面比例/缩放”。
                strokePaint.color = iconColor
                strokePaint.strokeWidth = (w.coerceAtMost(h) * 0.1f).coerceAtLeast(2f)
                val left = w * 0.18f
                val right = w * 0.82f
                val arm = w * 0.18f
                canvas.drawLine(left, top, left + arm, top, strokePaint)
                canvas.drawLine(left, top, left, top + arm, strokePaint)
                canvas.drawLine(right - arm, top, right, top, strokePaint)
                canvas.drawLine(right, top, right, top + arm, strokePaint)
                canvas.drawLine(left, bottom - arm, left, bottom, strokePaint)
                canvas.drawLine(left, bottom, left + arm, bottom, strokePaint)
                canvas.drawLine(right - arm, bottom, right, bottom, strokePaint)
                canvas.drawLine(right, bottom - arm, right, bottom, strokePaint)
            }
        }
    }
}

private class ChevronDownView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TextTertiary
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.strokeWidth = (w.coerceAtMost(h) * 0.13f).coerceAtLeast(2f)
        canvas.drawLine(w * 0.20f, h * 0.35f, w * 0.50f, h * 0.65f, paint)
        canvas.drawLine(w * 0.50f, h * 0.65f, w * 0.80f, h * 0.35f, paint)
    }
}

private class WarningGlyphView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TextSecondary
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TextSecondary
        style = Paint.Style.FILL
    }
    private val stem = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = w.coerceAtMost(h) * 0.42f
        ringPaint.strokeWidth = (w.coerceAtMost(h) * 0.085f).coerceAtLeast(2f)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val stemHalf = (w.coerceAtMost(h) * 0.05f).coerceAtLeast(1.5f)
        stem.set(cx - stemHalf, h * 0.28f, cx + stemHalf, h * 0.58f)
        canvas.drawRoundRect(stem, stemHalf, stemHalf, markPaint)
        canvas.drawCircle(cx, h * 0.70f, stemHalf * 1.15f, markPaint)
    }
}

// 播放请求 User-Agent:必须用桌面浏览器标识。部分上游 CDN(如 bilibili)对
// 含 "Android" 的 UA 做防盗链拦截会返回 403(实测桌面 Chrome / 裸 Mozilla 可 206,
// 任意 Android UA 均 403),与下发的 Referer/Origin(bilibili.com 网页源)保持一致。
private const val PlaybackUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MaxPlaybackRetries = 3
// 判定“播放已稳定”所需的连续顺畅播放时长。需明显大于单次恢复退避(≤4.5s)+ 缓冲,
// 以可靠区分“真正在正常播放”与“就绪后数秒又失败”的流;后者不会被计时器误判为稳定,
// 因而无法复位恢复额度,可在封顶次数内退场而非无限重试。
private const val PlaybackStabilityResetMs = 12_000L
private const val MaxThumbnailBytes = 6 * 1024 * 1024
private const val BackdropAlpha = 0.55f
// 瞬时 5xx/网络抖动最多重试 2 次,退避 400ms / 1200ms,总附加等待 ≤ 1.6s。
private const val MaxThumbnailRetries = 2
private val ThumbnailRetryBackoffMs = longArrayOf(400L, 1200L)

private class ThumbnailHttpException(val code: Int) : IOException("Thumbnail HTTP $code") {
    val isTransient: Boolean get() = code.isTransientHttpStatusCode()
}
private const val ViewTypeVideo = 1
private const val ViewTypeFooter = 2
private const val FooterStableId = Long.MIN_VALUE
private const val CategorySelectDebounceMs = 280L
private val ThumbnailFetchSemaphore = Semaphore(4)
private val ThumbnailHttpClient = AppHttpClient.newBuilder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()
private val PlaybackRequestHeaderDefaults = mapOf(
    "Accept" to "*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

private fun playbackRequestHeaders(companionHeaders: Map<String, String>): Map<String, String> {
    if (companionHeaders.isEmpty()) return PlaybackRequestHeaderDefaults
    val requestHeaders = LinkedHashMap<String, String>(companionHeaders)
    PlaybackRequestHeaderDefaults.forEach { (name, value) ->
        if (!requestHeaders.containsHeader(name)) {
            requestHeaders[name] = value
        }
    }
    return requestHeaders
}

private fun Map<String, String>.containsHeader(name: String): Boolean =
    keys.any { it.equals(name, ignoreCase = true) }

private fun playbackErrorMessage(error: PlaybackException): String {
    val responseCode = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
    return when (responseCode) {
        401, 403 -> "播放地址已失效或被拒绝，请重试"
        404, 410 -> "播放资源不可用，请重试或换个视频"
        502, 503, 504 -> "该视频来源暂不可用，请稍后重试或换个视频"
        else -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> "当前设备不支持该视频编码，可能需要服务端提供 H.264/AAC 版本"
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "视频解码失败，可能是编码、码率或分辨率超出当前设备能力"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> "当前播放格式不受支持，请换个清晰度或让服务端转码"
            else -> if (error.findCause<HttpDataSource.HttpDataSourceException>() != null) {
                "播放连接中断，请稍后重试"
            } else {
                "视频播放失败，请稍后重试"
            }
        }
    }
}

// 过期/拒绝的短期播放 URL(401/403)、网关超时/限流/瞬时 5xx,以及 Bilibili CDN
// 读首包时偶发的连接重置,都可通过刷新播放地址拿到新地址/新 CDN 后恢复。
private fun isRecoverablePlaybackError(error: PlaybackException): Boolean {
    val responseCode = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
        ?: return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> false
            else -> error.findCause<HttpDataSource.HttpDataSourceException>() != null
        }
    return responseCode == 401 || responseCode == 403 || responseCode.isTransientHttpStatusCode()
}

private fun shouldUseProxyPlaybackFallback(error: PlaybackException, stream: PlaybackOption): Boolean {
    return isRecoverablePlaybackError(error)
}

private fun playbackMimeType(mimeType: String?): String {
    return when (mimeType?.trim()?.lowercase()) {
        "application/dash+xml", "application/mpd", "application/dash" -> MimeTypes.APPLICATION_MPD
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        -> MimeTypes.APPLICATION_M3U8
        "video/webm" -> MimeTypes.VIDEO_WEBM
        "video/mp4", null -> MimeTypes.VIDEO_MP4
        else -> mimeType
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
