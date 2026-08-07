package com.example.mediacenter.ui.home

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import android.view.inputmethod.EditorInfo
import android.util.TypedValue
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.exitSearchMode
import com.example.mediacenter.ui.homeCategoryKey
import com.example.mediacenter.ui.homeDefaultCategoryTarget
import com.example.mediacenter.ui.homeFocusTargetFor
import com.example.mediacenter.ui.requestHomeFocus
import com.example.mediacenter.ui.setContent
import com.example.mediacenter.ui.showSystemInfoDialog
import com.example.mediacenter.ui.components.appBackground
import com.example.mediacenter.ui.components.centeredLoading
import com.example.mediacenter.ui.components.errorState
import com.example.mediacenter.ui.components.emptyState
import com.example.mediacenter.ui.components.homeInlineError
import com.example.mediacenter.ui.components.loadingScrim
import com.example.mediacenter.ui.components.circleIconButton
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.glassText
import com.example.mediacenter.ui.components.iconPill
import com.example.mediacenter.ui.components.gridColumns
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.config.CategorySelectDebounceMs
import com.example.mediacenter.ui.config.DIAGNOSTIC_LEFT_PRESS_COUNT
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusNoCategoryKey
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.ui.theme.SEARCH_HOT_KEYWORDS
import com.example.mediacenter.ui.views.PillIcon
import com.example.mediacenter.ui.views.PillIconView

internal fun MainActivity.renderHome(state: MediaUiState) {
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
            state.error != null && state.items.isEmpty() -> body.addView(errorState(state.error, viewModel::retry, state.autoRetrying), frameMatch())
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

internal fun MainActivity.homeTopBar(
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
                        currentState.items.firstOrNull()?.let { video ->
                            homeFocusArea = HomeFocusArea.Video
                            requestHomeFocus(HomeFocusTarget.Video(video.id))
                            true
                        } ?: false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        categories.lastOrNull()?.id?.let { id ->
                            homeFocusArea = HomeFocusArea.Category
                            requestHomeFocus(HomeFocusTarget.Category(id))
                            true
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
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // 焦点已在最左分类时再按左键:边界拦截(焦点不动),
                            // 并累计次数——连续按 5 次触发系统诊断弹窗。
                            // 设计:只在最左分类的左键计数,避免与正常分类切换混淆;
                            // 返回 true 始终消费(保持边界不动行为),无论是否触发弹窗。
                            if (index == 0) {
                                leftEdgePressCount++
                                if (leftEdgePressCount >= DIAGNOSTIC_LEFT_PRESS_COUNT) {
                                    leftEdgePressCount = 0
                                    showSystemInfoDialog()
                                }
                                true
                            } else {
                                // 切到非首分类时重置计数(用户离开了最左边界)。
                                leftEdgePressCount = 0
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index == categories.lastIndex) {
                                cancelPendingCategorySelect()
                                homeFocusArea = HomeFocusArea.Search
                                requestHomeFocus(HomeFocusTarget.Search)
                                true
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

        // 分类加载失败(categories 为空)时分类栏也要有可聚焦元素:
        // 1) 遥控器始终能进入分类行——"最左分类连按左键 5 次"调出系统诊断
        //    弹窗的入口不能因为加载失败而消失;
        // 2) 占位 chip 按 OK 直接重试,不依赖错误卡片上的"重试"按钮。
        if (categories.isEmpty()) {
            val placeholder = pill(
                text = "分类加载失败，按 OK 重试",
                selected = false,
                heightDp = 44,
                horizontalPaddingDp = 22,
                onClick = { viewModel.retry() },
            ).apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // 同真实分类 chip:焦点已在此(最左)时连按左键累计触发诊断弹窗。
                            leftEdgePressCount++
                            if (leftEdgePressCount >= DIAGNOSTIC_LEFT_PRESS_COUNT) {
                                leftEdgePressCount = 0
                                showSystemInfoDialog()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        else -> false
                    }
                }
            }
            registerFocusTarget(HomeFocusTarget.Category(HomeFocusNoCategoryKey), placeholder)
            chipRow.addView(placeholder, LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(12)))
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

internal fun MainActivity.searchModeBar(
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
            Colors.TEXT_PRIMARY,
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
                    currentState.items.firstOrNull()?.let { video ->
                        homeFocusArea = HomeFocusArea.Video
                        requestHomeFocus(HomeFocusTarget.Video(video.id))
                        true
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
                currentState.items.firstOrNull()?.let { video ->
                    homeFocusArea = HomeFocusArea.Video
                    requestHomeFocus(HomeFocusTarget.Video(video.id))
                    true
                } ?: false
            } else {
                false
            }
        }
        return row
    }

internal fun MainActivity.searchPanel(state: MediaUiState): View {
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
            setIconColor(Colors.TEXT_TERTIARY)
        }
        inputWrap.addView(inputIcon, LinearLayout.LayoutParams(dp(22), dp(22)).withEndMargin(dp(14)))
        val input = EditText(this).apply {
            setText(state.query)
            hint = "输入关键词"
            setSingleLine(true)
            setTextColor(Colors.TEXT_PRIMARY)
            setHintTextColor(Colors.TEXT_TERTIARY)
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
                    if (focused) Colors.APPLE_BLUE else Colors.GlassBorder,
                )
                inputIcon.setIconColor(if (focused) Colors.TEXT_PRIMARY else Colors.TEXT_TERTIARY)
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

        panel.addView(label("热门关键词", 13f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(28)))
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

internal fun MainActivity.clearHomeVideoList() {
        cancelPendingCategorySelect()
        homeVideoRecycler?.adapter = null
        homeVideoRecycler = null
        homeVideoAdapter = null
    }

internal fun MainActivity.scheduleCategorySelect(category: ContentCategory) {
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

internal fun MainActivity.cancelPendingCategorySelect() {
        pendingCategorySelect?.let { root.removeCallbacks(it) }
        pendingCategorySelect = null
    }

internal fun MainActivity.resetHomeDetailsPrefetch() {
        if (prefetchedVisibleVideoIds.isEmpty()) return
        prefetchedVisibleVideoIds = emptyList()
    }

internal fun MainActivity.prefetchVisibleHomeDetails(videos: List<VideoSummary>) {
        val ids = videos.map { it.id }
        if (ids == prefetchedVisibleVideoIds) return
        if (ids.isEmpty()) {
            resetHomeDetailsPrefetch()
            return
        }
        prefetchedVisibleVideoIds = ids
    }

