package com.example.mediacenter.ui.home

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.view.isNotEmpty
import android.view.inputmethod.EditorInfo
import android.util.TypedValue
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.SearchHistory
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.exitSearchMode
import com.example.mediacenter.ui.homeCategoryKey
import com.example.mediacenter.ui.homeDefaultCategoryTarget
import com.example.mediacenter.ui.homeFocusTargetFor
import com.example.mediacenter.ui.requestHomeFocus
import com.example.mediacenter.ui.setContent
import com.example.mediacenter.ui.showSystemInfoDialog
import com.example.mediacenter.ui.showProviderDialog
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
import com.example.mediacenter.ui.components.horizontalFocusStrip
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
import com.example.mediacenter.ui.theme.HomeContinueTabId
import com.example.mediacenter.ui.theme.SEARCH_HOT_KEYWORDS
import com.example.mediacenter.ui.theme.YOUTUBE_SEARCH_HOT_KEYWORDS
import com.example.mediacenter.ui.views.PillIcon
import com.example.mediacenter.ui.views.PillIconView

internal fun MainActivity.renderHome(state: MediaUiState) {
        // 首次进入且分类已就绪：焦点明确落在当前分类标签，而不是搜索按钮。
        // 老人打开电视后左右键即可换类，不需要先找到/避开顶栏操作。
        if (!homeInitialFocusApplied && !searchVisible && state.query.isBlank() &&
            state.playing == null && state.selected == null && state.categories.isNotEmpty()
        ) {
            val allCats = listOf(ContentCategory(HomeContinueTabId, "继续观看")) + state.categories
            val initialCategory = state.selectedCategoryId
                ?.takeIf { id -> allCats.any { it.id == id } }
                ?: allCats.first().id
            homeFocusArea = HomeFocusArea.Category
            pendingHomeFocus = HomeFocusTarget.Category(initialCategory)
        }
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
                homeTopBar(state, ::registerFocusTarget),
                linearMatch(),
            )
        } else {
            content.addView(searchModeBar(state.query, ::registerFocusTarget), linearMatch())
        }
        if (state.query.isBlank()) {
            content.addView(homeSectionHeader(state), linearMatch())
        }
        // 首页只有一个分类网格：分类标签在顶栏，不再有 Hero / 时长筛选 /
        // “查看全部”这类需要二次瞄准的入口。
        val body = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        content.addView(body, LinearLayout.LayoutParams(match(), 0, 1f))
        if (state.items.isEmpty()) {
            resetHomeDetailsPrefetch()
        }
        val displayItems = state.items
        val isContinue = state.selectedCategoryId == HomeContinueTabId
        when {
            state.loading && state.items.isEmpty() -> body.addView(centeredLoading("正在加载…"), frameMatch())
            state.error != null && state.items.isEmpty() -> body.addView(errorState(state.error, viewModel::retry, state.autoRetrying), frameMatch())
            displayItems.isEmpty() -> {
                val firstNormal = state.categories.firstOrNull()
                val emptyMsg = when {
                    state.query.isNotBlank() -> "没有找到相关内容"
                    isContinue -> "还没有观看记录"
                    else -> "当前分类暂无内容"
                }
                val emptyDetail = if (isContinue) "看过的视频会自动出现在这里，方便接着看" else null
                val emptyAction = if (isContinue && firstNormal != null) {
                    "浏览推荐" to {
                        cancelPendingCategorySelect()
                        rememberedCategoryId = firstNormal.id
                        homeFocusArea = HomeFocusArea.Category
                        pendingHomeFocus = HomeFocusTarget.Category(firstNormal.id)
                        viewModel.selectCategory(firstNormal)
                    }
                } else null
                body.addView(
                    emptyState(
                        emptyMsg,
                        detail = emptyDetail,
                        action = emptyAction,
                    ),
                    frameMatch(),
                )
            }
            else -> {
                body.addView(
                    videoGrid(
                        items = displayItems,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        loadMoreError = state.loadMoreError,
                        onRetryLoadMore = viewModel::retryLoadMore,
                        focusTarget = focusTarget,
                        isContinue = isContinue,
                    ),
                    frameMatch(),
                )
                prefetchVisibleHomeDetails(state.items.take(gridColumns() * 2))
            }
        }
        // 旧数据刷新/分页失败时的横幅:之前以 Gravity.BOTTOM 叠在网格之上(后加的子
        // 画在网格上层),会盖住底部卡片的时长行与 footer;改为 content 的独立一行,
        // 由 LinearLayout 自动让出空间,不再遮挡任何内容。
        if (state.error != null && displayItems.isNotEmpty()) {
            content.addView(
                homeInlineError(state.error),
                LinearLayout.LayoutParams(match(), wrap()).apply { bottomMargin = dp(8) },
            )
        }

        if (state.loading && state.items.isNotEmpty()) {
            screen.addView(loadingScrim(), FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }

        setContent(screen)
        // 首次焦点循环只在"真实分类已存在且首屏无错误"时启动:没有真实分类时
        // 占位 chip 永远满足不了"已聚焦真实分类"的完成条件,旧实现会在弱网首屏/
        // 分类为空时每 180ms 重试 24 次并随每次 render 重开,持续抢走"重试/设置"
        // 的焦点。等分类到达后的 render 再启动(见 startHomeInitialFocusLoop)。
        if (!homeInitialFocusApplied && !searchVisible && state.query.isBlank() &&
            state.playing == null && state.selected == null &&
            state.error == null && state.categories.isNotEmpty()
        ) {
            startHomeInitialFocusLoop(screen)
        }
    }

// 首屏焦点循环:窗口/视图就绪前 requestFocus 会静默失败,有限重试(24×180ms)。
// 只在真实分类存在时调用;成功落到占位 chip 后若用户已把焦点移走(按方向键到
// 搜索/设置等),立即放弃,不再抢回。
internal fun MainActivity.startHomeInitialFocusLoop(screen: View) {
    val attempts = intArrayOf(0)
    var focusAppliedOnce = false
    val focusRunnable = object : Runnable {
        override fun run() {
            if (homeInitialFocusApplied) return
            if (attempts[0]++ >= 24) return
            val target = homeDefaultCategoryTarget(currentState) ?: return
            val view = screen.findViewWithTag<View>(target.viewTag())
            if (view == null) {
                screen.postDelayed(this, 180)
                return
            }
            if (focusAppliedOnce) {
                val current = screen.findFocus()
                if (current != null && current !== view && current !== screen) {
                    // 用户已经开始操作,不要再把焦点抢回分类。
                    return
                }
            }
            val realCategory = target is HomeFocusTarget.Category &&
                target.id != HomeFocusNoCategoryKey
            if (view.requestFocus() && view.isFocused) {
                focusAppliedOnce = true
                if (realCategory) {
                    homeInitialFocusApplied = true
                    pendingHomeFocus = null
                    return
                }
            }
            screen.postDelayed(this, 180)
        }
    }
    screen.postDelayed(focusRunnable, 180)
}

internal fun MainActivity.homeTopBar(
        state: MediaUiState,
        registerFocusTarget: (HomeFocusTarget, View) -> Unit,
    ): View {
        val categories = if (state.categories.isNotEmpty()) {
            listOf(ContentCategory(HomeContinueTabId, "继续观看")) + state.categories
        } else {
            state.categories
        }
        val selectedId = state.selectedCategoryId
        // 新版 TopBar 的两段式:品牌 + 右侧动作一行,分类 chip 独立一行。
        // 这样分类横向滚动不会画到视频源/设置按钮下面(旧版单行会重叠)。
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(6))
            clipChildren = false
            clipToPadding = false
            elevationCompat(dp(20).toFloat())
        }

        // 品牌锚点:与新版 TopBar 相同的蓝色播放标 + 字标。
        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
        }
        val brandMark = FrameLayout(this).apply {
            background = rounded(Colors.APPLE_BLUE, 9)
        }
        brandMark.addView(
            PillIconView(this, PillIcon.Play).apply { setIconColor(Colors.TEXT_PRIMARY) },
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
        )
        brand.addView(brandMark, LinearLayout.LayoutParams(dp(34), dp(34)))
        brand.addView(
            label("Media Center", 20f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(wrap(), wrap()).withStartMargin(dp(10)),
        )
        row.addView(brand, LinearLayout.LayoutParams(wrap(), dp(54)).withEndMargin(dp(16)))
        // 中间弹性空白,把右侧动作推到最右。
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // 右侧:当前视频源 + 设置 + 搜索;视频源仅多源时显示。
        val providerName = state.providers
            .firstOrNull { it.id == state.currentProvider }
            ?.let { it.name?.takeIf { name -> name.isNotBlank() } ?: it.id }
            ?: state.currentProvider
        val providerChip = if (state.providers.size > 1) {
            pill(
                text = providerName,
                selected = false,
                heightDp = 54,
                horizontalPaddingDp = 22,
                onClick = { showProviderDialog(state.providers, state.currentProvider) },
                onFocusChange = { focused -> if (focused) homeFocusArea = HomeFocusArea.Search },
            ).apply {
                // provider.name 来自服务端:过长会挤压右侧「设置/搜索」成空胶囊,
                // 限制最大宽度并省略,保证顶栏动作按钮始终可读。
                maxWidth = dp(280)
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            cancelPendingCategorySelect()
                            categories.lastOrNull()?.id?.let { id ->
                                homeFocusArea = HomeFocusArea.Category
                                requestHomeFocus(HomeFocusTarget.Category(id))
                                true
                            } ?: false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            requestHomeFocus(HomeFocusTarget.Settings)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                }
            }.also { registerFocusTarget(HomeFocusTarget.Provider, it) }
        } else {
            null
        }
        val settingsButton = iconPill(
            text = "设置",
            icon = PillIcon.Settings,
            selected = false,
            heightDp = 54,
            horizontalPaddingDp = 18,
            onClick = { showSystemInfoDialog() },
            onFocusChange = { focused -> if (focused) homeFocusArea = HomeFocusArea.Search },
        ).apply {
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (providerChip != null) {
                            requestHomeFocus(HomeFocusTarget.Provider)
                        } else {
                            cancelPendingCategorySelect()
                            categories.lastOrNull()?.id?.let { id ->
                                homeFocusArea = HomeFocusArea.Category
                                requestHomeFocus(HomeFocusTarget.Category(id))
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        requestHomeFocus(HomeFocusTarget.Search)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
        }.also { registerFocusTarget(HomeFocusTarget.Settings, it) }

        val searchButton = iconPill(
            text = "搜索",
            icon = PillIcon.Search,
            selected = false,
            heightDp = 54,
            horizontalPaddingDp = 18,
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
                        requestHomeFocus(HomeFocusTarget.Settings)
                        true
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
                heightDp = 54,
                horizontalPaddingDp = 24,
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
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            // 分类栏向上直接进入顶栏按钮区(设置):此前 UP 被吞掉,
                            // 用户必须一路右移到最右侧才能向上够到顶栏,很难发现。
                            // 落到「设置」后左键到视频源、右键到搜索,形成导航枢纽。
                            cancelPendingCategorySelect()
                            homeFocusArea = HomeFocusArea.Search
                            requestHomeFocus(HomeFocusTarget.Settings)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // 分类栏直接进入下方网格首个视频；没有 Hero/筛选行，
                            // 老人按一次下键就能开始选片。
                            cancelPendingCategorySelect()
                            homeFocusArea = HomeFocusArea.Video
                            currentState.items.firstOrNull()?.id
                                ?.let { requestHomeFocus(HomeFocusTarget.Video(it)) }
                            true
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
                                requestHomeFocus(
                                    if (providerChip != null) HomeFocusTarget.Provider else HomeFocusTarget.Settings,
                                )
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
            chipRow.addView(chip, LinearLayout.LayoutParams(wrap(), dp(54)).withEndMargin(dp(12)))
        }

        // 分类加载失败(categories 为空)时分类栏也要有可聚焦元素:
        // 1) 遥控器始终能进入分类行——"最左分类连按左键 5 次"调出系统诊断
        //    弹窗的入口不能因为加载失败而消失;
        // 2) 占位 chip 按 OK 直接重试,不依赖错误卡片上的"重试"按钮。
        if (categories.isEmpty()) {
            val loadingCategories = state.loading
            val placeholder = pill(
                text = if (loadingCategories) "正在加载分类…" else "分类加载失败，按 OK 重试",
                selected = false,
                heightDp = 44,
                horizontalPaddingDp = 22,
                onClick = { if (!loadingCategories) viewModel.retry() },
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
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            // 占位 chip 同样向上进入顶栏(与真实分类 chip 一致)。
                            homeFocusArea = HomeFocusArea.Search
                            requestHomeFocus(HomeFocusTarget.Settings)
                            true
                        }
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
            // 视口必须裁剪:否则 chipRow 会画到右侧视频源/设置按钮下面。
            clipChildren = true
            addView(chipRow)
            // 左右各留 4dp:首个/最末 chip 的 1.06 倍焦点放大(最大 ≈3.5dp)
            // 不会被视口裁掉边框;clipToPadding=false 允许放大画进 padding。
            setPadding(dp(4), 0, dp(4), 0)
        }
        if (providerChip != null) {
            row.addView(providerChip, LinearLayout.LayoutParams(wrap(), dp(54)).withStartMargin(dp(12)))
        }
        row.addView(settingsButton, LinearLayout.LayoutParams(wrap(), dp(54)).withStartMargin(dp(8)))
        row.addView(searchButton, LinearLayout.LayoutParams(wrap(), dp(54)).withStartMargin(dp(8)))
        container.addView(row, LinearLayout.LayoutParams(match(), wrap()))
        container.addView(
            scroll,
            LinearLayout.LayoutParams(match(), wrap()).apply { topMargin = dp(2) },
        )
        return container
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
            // 非滚动容器:关闭裁剪,给顶部按钮 1.05/1.06 倍聚焦放大留溢出空间
            // (旧实现「取消」按钮贴右边界,聚焦时右侧焦点框被行/面板边界裁平)。
            clipChildren = false
            clipToPadding = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
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
            SearchHistory.record(this, keyword)
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
        var firstRecentChip: View? = null
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
                    (firstRecentChip ?: firstKeywordChip)?.requestFocus() == true
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
                    (firstRecentChip ?: firstKeywordChip)?.requestFocus() == true
                }
                else -> false
            }
        }
        panel.addView(row, linearMatch())

        val recentSearches = SearchHistory.entries(this)
        if (recentSearches.isNotEmpty()) {
            val historyHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                clipChildren = false
                clipToPadding = false
            }
            historyHeader.addView(
                label("最近搜索", 13f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1),
                LinearLayout.LayoutParams(0, wrap(), 1f),
            )
            val clearHistoryChip = pill("清空历史", selected = false, heightDp = 36, horizontalPaddingDp = 14) {
                SearchHistory.clear(this)
                invalidateUi()
            }
            historyHeader.addView(clearHistoryChip, LinearLayout.LayoutParams(wrap(), dp(36)))
            panel.addView(historyHeader, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(22)))

            val historyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                clipChildren = false
                clipToPadding = false
            }
            recentSearches.forEach { query ->
                val chip = pill(query, selected = false, heightDp = 48, horizontalPaddingDp = 20) {
                    submitSearch(query)
                }.apply {
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    searchButton.requestFocus()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    firstKeywordChip?.requestFocus() == true
                                }
                                else -> false
                            }
                        } else false
                    }
                }
                if (firstRecentChip == null) firstRecentChip = chip
                historyRow.addView(
                    chip,
                    LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(10)),
                )
            }
            panel.addView(horizontalFocusStrip(historyRow, padH = 6, padV = 4), linearMatch())
        }

        val providerTitle = state.providers.firstOrNull { it.id == state.currentProvider }?.let { it.name ?: it.id } ?: state.currentProvider
        val hotTitle = if (providerTitle.isNotBlank()) "热门搜索 · $providerTitle" else "热门关键词"
        panel.addView(
            label(hotTitle, 13f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(if (recentSearches.isNotEmpty()) 18 else 28)),
        )
        val keywordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val hotKeywords = if (state.currentProvider == "youtube") {
            YOUTUBE_SEARCH_HOT_KEYWORDS
        } else {
            SEARCH_HOT_KEYWORDS
        }
        hotKeywords.forEach { keyword ->
            val chip = pill(keyword, selected = false, heightDp = 48, horizontalPaddingDp = 20) {
                submitSearch(keyword)
            }.apply {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        (firstRecentChip ?: searchButton).requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            if (firstKeywordChip == null) firstKeywordChip = chip
            keywordRow.addView(
                chip,
                // 之前父级固定 44dp 覆盖了 pill 的 48dp 最小热区,热词 chip 偏矮;
                // 与 48dp 焦点目标下限对齐。
                LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(10)),
            )
        }
        // 滚动容器必须裁剪(否则滚动时热词会画到页面留白/屏幕边缘),
        // 用 padding + clipToPadding=false 给 chip 焦点放大留余量。
        panel.addView(horizontalFocusStrip(keywordRow, padH = 6, padV = 4), linearMatch())
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


/** 分类标题：让用户一眼知道当前看的是哪一类，而不是只靠顶部选中的小胶囊。 */
internal fun MainActivity.homeSectionHeader(state: MediaUiState): View {
    val title = if (state.selectedCategoryId == HomeContinueTabId) {
        "继续观看"
    } else {
        state.categories
            .firstOrNull { it.id == state.selectedCategoryId }
            ?.title
            ?: "视频"
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(18), 0, dp(6))
        addView(
            label(title, 26f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1),
            linearMatch(),
        )
    }
}
