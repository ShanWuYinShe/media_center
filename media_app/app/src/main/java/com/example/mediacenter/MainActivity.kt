package com.example.mediacenter

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.data.VideoDetailsCache
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.player.TolerantDashManifestParser
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.MediaViewModel
import com.example.mediacenter.ui.MediaViewModelFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MediaViewModel by viewModels {
        MediaViewModelFactory(
            MediaRepository(
                api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
                categoryDetailsCache = VideoDetailsCache(cacheDir),
                searchDetailsCache = VideoDetailsCache(
                    cacheDir = cacheDir,
                    cacheDirectoryName = VideoDetailsCache.SearchCacheDirectoryName,
                    maxBytes = VideoDetailsCache.SearchMaxBytes,
                    maxAgeMillis = VideoDetailsCache.SearchMaxAgeMillis,
                ),
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
    private var homeFocusArea: HomeFocusArea = HomeFocusArea.Category
    private var rememberedCategoryId: String? = null
    private val rememberedVideoFocusByCategory = mutableMapOf<String, String>()
    private var pendingHomeFocus: HomeFocusTarget? = null
    private var selectedPlaybackOptionId: String? = null
    private var selectedPlaybackVideoId: String? = null
    private var detailFocusTarget: DetailFocusTarget = DetailFocusTarget.Play

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("MediaCenter", "MEDIA_SERVER_URL=${BuildConfig.MEDIA_SERVER_URL}")

        root = FrameLayout(this)
        setContentView(root)

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
                    render(state)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        if (currentState.playing != null) {
            viewModel.back()
        }
        releasePlayer()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun invalidateUi() {
        renderedState = null
        render(currentState)
    }

    private fun render(state: MediaUiState) {
        if (renderedState == state && renderedSearchVisible == searchVisible) return
        renderedState = state
        renderedSearchVisible = searchVisible

        when {
            state.playing != null -> renderPlayer(state)
            state.selected != null -> {
                releasePlayer()
                renderDetails(state)
            }
            else -> {
                releasePlayer()
                renderHome(state)
            }
        }
    }

    private fun renderHome(state: MediaUiState) {
        val screen = FrameLayout(this).apply {
            background = appBackground()
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), 0)
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
            content.addView(View(this), LinearLayout.LayoutParams(match(), dp(60)))
            content.addView(searchPanel(state), linearMatch())
            setContent(screen)
            return
        }

        if (state.query.isBlank()) {
            content.addView(searchIconRow(::registerFocusTarget), linearMatch())
            content.addView(
                categoryRail(state.categories, state.selectedCategoryId, ::registerFocusTarget),
                linearMatch(),
            )
        } else {
            content.addView(searchModeBar(state.query, ::registerFocusTarget), linearMatch())
        }

        val body = FrameLayout(this)
        content.addView(body, LinearLayout.LayoutParams(match(), 0, 1f))
        when {
            state.loading && state.items.isEmpty() -> body.addView(centeredLoading("正在加载…"), frameMatch())
            state.error != null -> body.addView(errorState(state.error, viewModel::retry), frameMatch())
            state.items.isEmpty() -> body.addView(emptyState(if (state.query.isBlank()) "当前分类暂无内容" else "没有找到相关内容"), frameMatch())
            else -> {
                body.addView(
                    videoGrid(
                        items = state.items,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        registerFocusTarget = ::registerFocusTarget,
                    ),
                    frameMatch(),
                )
                viewModel.prefetchVisibleDetails(state.items.take(gridColumns() * 2))
            }
        }

        if (state.loading && state.items.isNotEmpty()) {
            screen.addView(loadingScrim(), FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }

        setContent(screen)
    }

    private fun searchIconRow(
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
            clipChildren = false
            clipToPadding = false
        }
        row.addView(View(this), LinearLayout.LayoutParams(0, dp(54), 1f))
        val searchButton = circleIconButton(
            icon = PillIcon.Search,
            onClick = {
                homeFocusArea = HomeFocusArea.Search
                searchVisible = true
                invalidateUi()
            },
        ).apply {
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return@setOnKeyListener false
                }
                homeDefaultCategoryTarget(currentState)?.let { target ->
                    homeFocusArea = HomeFocusArea.Category
                    requestHomeFocus(target)
                } ?: false
            }
        }
        registerFocusTarget(HomeFocusTarget.Search, searchButton)
        row.addView(searchButton, LinearLayout.LayoutParams(wrap(), dp(54)))
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
            setPadding(0, dp(12), 0, 0)
        }
        panel.addView(label("搜索", 30f, Colors.TextPrimary, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withStartMargin(dp(2)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
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

    private fun categoryRail(
        categories: List<ContentCategory>,
        selectedId: String?,
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            clipChildren = false
            clipToPadding = false
        }
        categories.forEach { category ->
            val chip = pill(
                text = category.title,
                selected = category.id == selectedId,
                heightDp = 44,
                horizontalPaddingDp = 22,
                onClick = {
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
                    }
                },
            ).apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            homeFocusArea = HomeFocusArea.Search
                            requestHomeFocus(HomeFocusTarget.Search)
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
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
                        else -> false
                    }
                }
            }
            registerFocusTarget(HomeFocusTarget.Category(category.id), chip)
            row.addView(chip, LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(12)))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            addView(row)
        }
    }

    private fun videoGrid(
        items: List<VideoSummary>,
        canLoadMore: Boolean,
        loadingMore: Boolean,
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            viewTreeObserver.addOnScrollChangedListener {
                val content = getChildAt(0) ?: return@addOnScrollChangedListener
                if (canLoadMore && !loadingMore && scrollY + height >= content.height - dp(360)) {
                    viewModel.loadMore()
                }
            }
        }
        val grid = GridLayout(this).apply {
            columnCount = gridColumns()
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(16), 0, dp(32))
        }
        val cardWidth = posterCardWidth(grid.columnCount)
        val cardHeight = (cardWidth * 1.50f).toInt()
        items.forEachIndexed { index, video ->
            val card = posterCard(
                video = video,
                index = index,
                columns = grid.columnCount,
            )
            registerFocusTarget(HomeFocusTarget.Video(video.id), card)
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(dp(8), dp(8), dp(8), dp(18))
            })
        }
        if (canLoadMore) {
            grid.addView(loadMoreIndicator(loadingMore), GridLayout.LayoutParams().apply {
                width = resources.displayMetrics.widthPixels - dp(112)
                height = dp(72)
                columnSpec = GridLayout.spec(0, grid.columnCount)
                setMargins(0, dp(4), 0, dp(18))
            })
        }
        scroll.addView(grid)
        return scroll
    }

    private fun posterCard(
        video: VideoSummary,
        index: Int,
        columns: Int,
    ): View {
        val card = FrameLayout(this).apply {
            clipChildren = false
            isFocusable = true
            isClickable = true
            foreground = null
            background = rounded(Color.TRANSPARENT, 16, 1, Color.TRANSPARENT)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener { viewModel.openDetails(video) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener false
                }
                if (index >= columns) return@setOnKeyListener false
                homeDefaultCategoryTarget(currentState)?.let { target ->
                    homeFocusArea = HomeFocusArea.Category
                    requestHomeFocus(target)
                } ?: false
            }
        }
        val imageWrap = FrameLayout(this).apply {
            background = rounded(Colors.SurfaceElevated, 14, 1, Colors.GlassBorder)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Colors.SurfaceElevated)
        }
        imageWrap.addView(image, frameMatch())
        val initials = label(video.title.take(2).ifBlank { "影" }, 42f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        imageWrap.addView(initials, frameMatch())
        loadImage(video.thumbnailUrl, image, initials, video.title)
        imageWrap.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(22, 255, 255, 255), Color.TRANSPARENT),
                )
            },
            frameMatch(),
        )
        imageWrap.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.argb(220, 0, 0, 0)),
                )
            },
            frameMatch(),
        )
        val focusIndicator = View(this).apply {
            setBackgroundColor(Colors.AppleBlue)
            alpha = 0f
        }
        imageWrap.addView(focusIndicator, FrameLayout.LayoutParams(match(), dp(3), Gravity.BOTTOM))
        card.addView(imageWrap, frameMatch())

        val title = label(video.title, 14f, Colors.TextPrimary, bold = true, maxLines = 1).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        val meta = label(videoMeta(video), 11f, Color.argb(170, 255, 255, 255), maxLines = 1)
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(title, linearMatch())
            addView(meta, linearMatch())
        }
        imageWrap.addView(textBlock, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))
        applyCardFocus(card) { focused ->
            if (focused) {
                homeFocusArea = HomeFocusArea.Video
                rememberedVideoFocusByCategory[homeCategoryKey(currentState)] = video.id
            }
            focusIndicator.animate().alpha(if (focused) 1f else 0f).setDuration(120).start()
        }
        return card
    }

    private fun loadMoreIndicator(loadingMore: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
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

    private fun renderDetails(state: MediaUiState) {
        val details = state.selected ?: return
        if (selectedPlaybackVideoId != details.id) {
            selectedPlaybackVideoId = details.id
            selectedPlaybackOptionId = null
            detailFocusTarget = DetailFocusTarget.Play
        }
        val screen = FrameLayout(this).apply { background = appBackground() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(54), dp(72), dp(54))
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
            background = rounded(Colors.SurfaceElevated, 22, 1, Colors.GlassBorder)
        }
        val heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Colors.SurfaceElevated)
        }
        val heroInitials = label(details.title.take(2).ifBlank { "影" }, 64f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
        }
        hero.addView(heroImage, frameMatch())
        hero.addView(heroInitials, frameMatch())
        loadImage(details.thumbnailUrl, heroImage, heroInitials, details.title)
        hero.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
                )
            },
            frameMatch(),
        )
        row.addView(hero, LinearLayout.LayoutParams(dp(520), dp(320)).withEndMargin(dp(56)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(info, LinearLayout.LayoutParams(0, match(), 1f))

        val metadata = listOfNotNull(
            details.durationSeconds?.let { formatDurationSeconds(it) },
            details.year,
            details.creators.firstOrNull(),
        ).joinToString(" · ")
        if (metadata.isNotBlank()) {
            info.addView(label(metadata, 14f, Colors.TextTertiary, bold = true, maxLines = 1), linearMatch())
        }
        info.addView(label(details.title, 34f, Colors.TextPrimary, bold = true, maxLines = 2), linearMatch())
        info.addView(label(details.description.ifBlank { "暂无简介" }, 17f, Colors.TextSecondary, maxLines = 5), linearMatch())

        if (details.description.length > 160) {
            info.addView(
                pill("查看完整简介", selected = false, heightDp = 38, horizontalPaddingDp = 18) {
                    showDescriptionDialog(details.title, details.description)
                },
                LinearLayout.LayoutParams(wrap(), dp(38)).withTopMargin(dp(8)),
            )
        }

        val options = details.playbackOptions
        var detailErrorRetry: () -> Unit = viewModel::refreshSelectedPlayback
        if (options.isEmpty()) {
            info.addView(label("暂无可播放视频", 15f, Colors.TextTertiary, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(24)))
            if (detailFocusTarget == DetailFocusTarget.Play) {
                detailFocusTarget = DetailFocusTarget.Refresh
            }
            val refresh = pill(
                "刷新播放地址",
                selected = false,
                heightDp = 44,
                horizontalPaddingDp = 22,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Refresh
                },
                onClick = {
                    detailFocusTarget = DetailFocusTarget.Play
                    viewModel.refreshSelectedPlayback()
                },
            )
            registerDetailFocus(DetailFocusTarget.Refresh, refresh)
            info.addView(refresh, LinearLayout.LayoutParams(wrap(), dp(44)).withTopMargin(dp(12)))
        } else {
            val selectedOption = options.firstOrNull { it.id == selectedPlaybackOptionId } ?: options.first()
            selectedPlaybackOptionId = selectedOption.id
            detailErrorRetry = { viewModel.play(selectedOption) }
            val playButton = primaryPill(
                text = buildString {
                    append("播放")
                    selectedOption.label.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                heightDp = 58,
                horizontalPaddingDp = 38,
                textSizeSp = 20f,
                icon = PillIcon.Play,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Play
                },
                onClick = { viewModel.play(selectedOption) },
            )
            registerDetailFocus(DetailFocusTarget.Play, playButton)
            info.addView(playButton, LinearLayout.LayoutParams(wrap(), dp(58)).withTopMargin(dp(24)))

            if (options.size > 1) {
                info.addView(label("清晰度", 13f, Colors.TextTertiary, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(20)))
                val optionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(0, dp(10), 0, 0)
                }
                options.take(5).forEach { option ->
                    val chip = pill(
                        text = option.label.ifBlank { "播放" },
                        selected = option.id == selectedOption.id,
                        heightDp = 44,
                        horizontalPaddingDp = 22,
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
                        LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(14)),
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
        if (currentPlayerUrl == stream.url && root.childCount > 0) return

        val playbackUrl = stream.url
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(PlaybackUserAgent)
            .setDefaultRequestProperties(playbackRequestHeaders(playbackUrl))

        releasePlayer()
        val nextPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(playbackUrl)
                    .setMimeType(playbackMimeType(stream.mimeType))
                    .build()
                if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD) {
                    setMediaSource(
                        DashMediaSource.Factory(dataSourceFactory)
                            .setManifestParser(TolerantDashManifestParser())
                            .createMediaSource(mediaItem),
                    )
                } else {
                    setMediaItem(mediaItem)
                }
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            viewModel.playNext()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w("MediaCenterPlayer", "Playback failed for ${stream.id}", error)
                        viewModel.playbackFailed("视频播放失败，请稍后重试")
                    }
                })
                prepare()
            }
        player = nextPlayer
        currentPlayerUrl = playbackUrl

        val overlay = playerOverlay(stream, nextPlayer, state)
        val hideOverlay = Runnable {
            overlay.animate().alpha(0f).setDuration(400).start()
        }
        fun showOverlay() {
            overlay.animate().cancel()
            overlay.alpha = 1f
            overlay.removeCallbacks(hideOverlay)
            overlay.postDelayed(hideOverlay, 4_000)
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

        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
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
                    else -> false
                }
            }
        }
        val playerView = PlayerView(this).apply {
            player = nextPlayer
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        screen.addView(playerView, frameMatch())
        screen.addView(overlay, frameMatch())
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
        val overlay = FrameLayout(this)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(56), dp(28), dp(56), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(166, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        top.addView(label("正在播放 · ${stream.label.ifBlank { "默认" }}", 18f, Color.WHITE, bold = true, maxLines = 1), linearMatch())
        overlay.addView(top, FrameLayout.LayoutParams(match(), wrap(), Gravity.TOP))

        val glyph = PlaybackGlyphView(this).apply {
            setPlaying(player.playWhenReady && player.playbackState != Player.STATE_ENDED)
        }
        val center = FrameLayout(this).apply {
            background = rounded(Color.argb(46, 255, 255, 255), 999, 2, Color.argb(140, 255, 255, 255))
            addView(glyph, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        overlay.addView(center, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(56), dp(48), dp(56), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(184, 0, 0, 0)),
            )
        }

        val progressTrack = FrameLayout(this).apply {
            background = rounded(Color.argb(56, 255, 255, 255), 3)
        }
        val progressFill = View(this).apply {
            background = rounded(Colors.AppleBlue, 3)
        }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, dp(5), Gravity.START or Gravity.CENTER_VERTICAL))
        bottom.addView(progressTrack, LinearLayout.LayoutParams(match(), dp(5)))

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val hint = label(
            buildString {
                append("OK 播放/暂停 · ←→ 快退/快进 10 秒")
                if (hasPrevious) append(" · ↑ 上一个")
                if (hasNext) append(" · ↓ 下一个")
            },
            13f,
            Colors.TextTertiary,
            maxLines = 1,
        )
        val time = label("0:00 / 0:00", 15f, Colors.TextSecondary, maxLines = 1)
        controlsRow.addView(hint, LinearLayout.LayoutParams(0, wrap(), 1f))
        controlsRow.addView(time, LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(16)))
        bottom.addView(controlsRow, linearMatch())
        if (previousTitle != null) {
            bottom.addView(label("上一个:$previousTitle", 13f, Colors.TextSecondary, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(8)))
        }
        if (nextTitle != null) {
            bottom.addView(label("下一个:$nextTitle", 13f, Colors.TextSecondary, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(8)))
        }
        overlay.addView(bottom, FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM))

        fun updateProgress() {
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val position = player.currentPosition.coerceAtLeast(0L)
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            progressFill.layoutParams = FrameLayout.LayoutParams((progressTrack.width * fraction).toInt(), dp(5), Gravity.START or Gravity.CENTER_VERTICAL)
            time.text = "${formatTime(position)} / ${formatTime(duration)}"
        }
        val progressTick = object : Runnable {
            override fun run() {
                if (overlay.parent == null) return
                updateProgress()
                overlay.postDelayed(this, 300)
            }
        }
        fun updatePlaybackGlyph() {
            glyph.setPlaying(player.playWhenReady && player.playbackState != Player.STATE_ENDED)
            center.visibility = View.VISIBLE
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
            card.addView(label(title, 24f, Colors.TextPrimary, bold = true, maxLines = 2), linearMatch())
            val scroll = ScrollView(this@MainActivity)
            scroll.addView(label(description, 17f, Colors.TextSecondary, maxLines = Int.MAX_VALUE), frameMatch())
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

    private fun setContent(view: View) {
        root.removeAllViews()
        root.addView(view, frameMatch())
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        currentPlayerUrl = null
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
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        box.addView(label(text, 16f, Colors.TextSecondary, bold = true, maxLines = 1), LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(14)))
        return box
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
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
        }
        box.addView(label(message, 18f, Colors.TextPrimary, bold = true, maxLines = 3), linearWrap())
        val retry = pill("重试", selected = true, heightDp = 50, horizontalPaddingDp = 32, onClick = onRetry)
        box.addView(retry, LinearLayout.LayoutParams(wrap(), dp(50)).withTopMargin(dp(16)))
        retry.post { retry.requestFocus() }
        return box
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

    private fun emptyState(message: String): View {
        return label(message, 18f, Colors.TextTertiary, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
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
            button.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
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
            val contentColor = if (focused) Colors.TextPrimary else Colors.DeepSpace
            textView.setTextColor(contentColor)
            iconView?.setIconColor(contentColor)
            row.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
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
                selected -> rounded(Colors.AppleBlue, 999, 2, Colors.AppleBlue)
                else -> rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            }
            setContentColor(if (focused || selected) Colors.TextPrimary else Colors.TextSecondary)
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
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
            view.background = rounded(Color.TRANSPARENT, 18, if (focused) 3 else 0, if (focused) Colors.GlassBorderFocused else Color.TRANSPARENT)
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        update(false)
        view.setOnFocusChangeListener { _, focused ->
            update(focused)
            onFocusChange?.invoke(focused)
        }
    }

    private fun loadImage(url: String?, image: ImageView, placeholder: View, title: String) {
        val imageUrl = url?.takeIf { it.isNotBlank() }
        image.tag = imageUrl
        image.setImageDrawable(null)
        placeholder.visibility = View.VISIBLE
        if (imageUrl == null) return
        ImageMemoryCache.get(imageUrl)?.let {
            image.setImageBitmap(it)
            placeholder.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmap(imageUrl) }
            if (bitmap != null) {
                ImageMemoryCache.put(imageUrl, bitmap)
            } else {
                Log.w("MediaCenterImage", "Unable to load thumbnail for $title")
            }
            if (image.tag == imageUrl && bitmap != null) {
                image.setImageBitmap(bitmap)
                placeholder.visibility = View.GONE
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", PlaybackUserAgent)
        }
        return try {
            connection.inputStream.use(BitmapFactory::decodeStream)
        } catch (error: Exception) {
            Log.w("MediaCenterImage", "Thumbnail request failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun appBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Colors.HeroGradientTop, Colors.DeepSpace),
        )
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

    private fun videoMeta(video: VideoSummary): String {
        return listOfNotNull(
            video.durationSeconds?.let { formatDurationSeconds(it) },
            video.year,
            video.creators.firstOrNull(),
        ).joinToString(" · ").ifBlank { "公开视频" }
    }

    private fun formatDurationSeconds(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val sec = total % 60
        val min = (total / 60) % 60
        val hr = total / 3600
        return if (hr > 0) {
            String.format("%d:%02d:%02d", hr, min, sec)
        } else {
            String.format("%d:%02d", min, sec)
        }
    }

    private fun formatTime(ms: Long): String {
        return formatDurationSeconds(ms / 1000)
    }

    private fun gridColumns(): Int {
        val width = resources.displayMetrics.widthPixels
        val available = width - dp(112)
        return max(1, (available + dp(16)) / (dp(130) + dp(16)))
    }

    private fun posterCardWidth(columns: Int): Int {
        val available = resources.displayMetrics.widthPixels - dp(112) - dp(16 * columns)
        return max(dp(130), available / columns)
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}

private object Colors {
    const val DeepSpace: Int = 0xFF000000.toInt()
    const val SurfaceElevated: Int = 0xFF0E1014.toInt()
    const val GlassBorderFocused: Int = 0xFFE5E5E7.toInt()
    const val TextPrimary: Int = 0xFFF5F5F7.toInt()
    const val TextSecondary: Int = 0xFFB0B3B8.toInt()
    const val TextTertiary: Int = 0xFF86868B.toInt()
    const val TextDanger: Int = 0xFFFF453A.toInt()
    const val AppleBlue: Int = 0xFF0A84FF.toInt()
    const val HeroGradientTop: Int = 0xFF111324.toInt()
    val GlassFill: Int = Color.argb(20, 255, 255, 255)
    val GlassFillStrong: Int = Color.argb(31, 255, 255, 255)
    val GlassBorder: Int = Color.argb(32, 255, 255, 255)
}

private object ImageMemoryCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
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

private class PillIconView(
    context: Context,
    private val icon: PillIcon,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
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
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = iconColor
                }
                val path = android.graphics.Path().apply {
                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.28f, h * 0.82f)
                    lineTo(w * 0.82f, h * 0.50f)
                    close()
                }
                canvas.drawPath(path, fill)
            }
        }
    }
}

private class PlaybackGlyphView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var playing: Boolean = true

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (playing) {
            val radius = w * 0.05f
            canvas.drawRoundRect(w * 0.24f, h * 0.18f, w * 0.46f, h * 0.82f, radius, radius, paint)
            canvas.drawRoundRect(w * 0.54f, h * 0.18f, w * 0.76f, h * 0.82f, radius, radius, paint)
        } else {
            val path = android.graphics.Path().apply {
                moveTo(w * 0.28f, h * 0.18f)
                lineTo(w * 0.28f, h * 0.82f)
                lineTo(w * 0.82f, h * 0.50f)
                close()
            }
            canvas.drawPath(path, paint)
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

private const val PlaybackUserAgent =
    "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private fun playbackRequestHeaders(url: String): Map<String, String> {
    val headers = mutableMapOf(
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )
    val host = runCatching { URI(url).host }.getOrNull() ?: return headers
    when {
        host.isHostOrSubdomain("bilivideo.com") || host.isHostOrSubdomain("bilibili.com") ->
            headers["Referer"] = "https://www.bilibili.com"
        host.isHostOrSubdomain("googlevideo.com") || host.isHostOrSubdomain("youtube.com") ->
            headers["Referer"] = "https://www.youtube.com"
    }
    return headers
}

private fun playbackMimeType(mimeType: String?): String {
    return when (mimeType?.lowercase()) {
        "application/dash+xml", "application/mpd", "application/dash" -> MimeTypes.APPLICATION_MPD
        "video/webm" -> MimeTypes.VIDEO_WEBM
        "video/mp4", null -> MimeTypes.VIDEO_MP4
        else -> mimeType
    }
}

private fun String.isHostOrSubdomain(domain: String): Boolean {
    return this == domain || endsWith(".$domain")
}
