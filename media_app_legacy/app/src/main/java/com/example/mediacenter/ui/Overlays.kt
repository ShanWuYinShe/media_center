package com.example.mediacenter.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.mediacenter.MainActivity
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.components.centeredLoading
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.roundedGradient
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.HomeContinueTabId
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusNoCategoryKey
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.ui.views.WarningGlyphView

internal fun MainActivity.setContent(view: View) {
        root.removeAllViews()
        playerRetryOverlay = null
        playerErrorOverlay = null
        root.addView(view, frameMatch())
    }

    // 播放等待遮罩:播放失败自动恢复显示「正在重试…」,播放页切清晰度显示
    // 「正在切换清晰度…」。旧实现只有恢复流程有提示,切档期间零反馈且旧画质
    // 继续播,用户以为按键失灵。
    // 遮罩结束(重建播放器或退出播放页)后移除。setContent 会清空 root,因此一并清引用。
internal fun MainActivity.updatePlayerRetryOverlay(show: Boolean, message: String = "正在重试…") {
        if (show) {
            if (playerRetryOverlay != null && playerRetryOverlayMessage == message) return
            playerRetryOverlay?.let { root.removeView(it) }
            val scrim = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(140, 0, 0, 0))
                isClickable = false
                addView(
                    centeredLoading(message),
                    FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER),
                )
            }
            playerRetryOverlay = scrim
            playerRetryOverlayMessage = message
            root.addView(scrim, frameMatch())
        } else {
            playerRetryOverlay?.let { root.removeView(it) }
            playerRetryOverlay = null
            playerRetryOverlayMessage = null
        }
    }

    // 播放失败且没有 details 信息可退(fallbackSelected 为 null)时,在播放画面上盖一层
    // 错误提示 + 重试按钮;避免把用户直接踢到主页。用户按 BACK 退出或点"重试"重建播放器。
internal fun MainActivity.updatePlayerErrorOverlay(error: String?) {
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
            background = roundedGradient(Colors.SURFACE_FOCUSED, Colors.SURFACE_ELEVATED, 30, 1, Colors.GlassBorder)
            minimumWidth = dp(360)
            elevationCompat(dp(18).toFloat())
        }
        val iconWrap = FrameLayout(this).apply {
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
        }
        iconWrap.addView(WarningGlyphView(this), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(iconWrap, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(
            label("播放失败", 20f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(16)),
        )
        card.addView(
            label(error, 15f, Colors.TEXT_SECONDARY, maxLines = 3).apply {
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


internal fun MainActivity.exitSearchMode() {
        searchVisible = false
        // 保留用户当前所在分类,不再固定跳回第一个分类(会丢失浏览位置)。
        val categoryId = currentState.selectedCategoryId
            ?: currentState.categories.firstOrNull()?.id
        if (categoryId != null) {
            pendingHomeFocus = HomeFocusTarget.Category(categoryId)
            rememberedCategoryId = categoryId
            homeFocusArea = HomeFocusArea.Category
        }
        // 关键:必须真正清空 state.query。旧实现只把 searchVisible 置 false:
        // 默认分类下 selectCategory(当前分类) 会直接 return,state 不变,于是
        // BACK /「清除」没有任何反应,用户被困在搜索结果里。
        // state 更新会经 StateFlow 触发重绘;这里不要再调 invalidateUi,
        // 否则会先用旧 state(关键词仍非空)重绘一帧搜索栏,出现闪烁。
        viewModel.exitSearch()
    }

internal fun MainActivity.requestHomeFocus(target: HomeFocusTarget) {
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
                return
            }
        }
        val view = root.findViewWithTag<View>(target.viewTag())
        if (view != null) {
            view.requestFocus()
            pendingHomeFocus = null
            return
        }
        invalidateUi()
    }

internal fun MainActivity.homeFocusTargetFor(state: MediaUiState): HomeFocusTarget? {
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

internal fun MainActivity.homeDefaultCategoryTarget(state: MediaUiState): HomeFocusTarget? {
        if (state.selectedCategoryId == HomeContinueTabId) {
            return HomeFocusTarget.Category(HomeContinueTabId)
        }
        val categoryId = state.selectedCategoryId
            ?.takeIf { id -> state.categories.any { it.id == id } }
            ?: rememberedCategoryId?.takeIf { id -> state.categories.any { it.id == id } }
            ?: state.categories.firstOrNull()?.id
        return categoryId?.let(HomeFocusTarget::Category)
            // categories 为空(加载失败)时把焦点落到占位 chip,保证分类栏
            // 有初始焦点——遥控器左键连按 5 次即可调出系统诊断弹窗排查。
            ?: if (state.categories.isEmpty()) HomeFocusTarget.Category(HomeFocusNoCategoryKey) else null
    }

internal fun MainActivity.homeCategoryKey(state: MediaUiState): String {
        return state.selectedCategoryId ?: HomeFocusNoCategoryKey
    }

