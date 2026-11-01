package com.example.mediacenter.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.videoMeta
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.displayItems
import com.example.mediacenter.ui.homeDefaultCategoryTarget
import com.example.mediacenter.ui.loadImage
import com.example.mediacenter.ui.requestHomeFocus
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.ui.theme.HomeFocusTarget
import com.example.mediacenter.ui.views.ThumbnailImageView

/**
 * 首页 Hero:与新版 Compose 首页保持同一视觉语言(大图、双层遮罩、
 * 精选徽标、主/次操作),但用原生 View 实现以继续支持 API 19 老盒子。
 */
internal fun MainActivity.homeHero(
    video: VideoSummary,
    registerFocusTarget: (HomeFocusTarget, View) -> Unit,
): View {
    val hero = FrameLayout(this).apply {
        background = rounded(Colors.SURFACE_ELEVATED, 18)
        clipChildren = true
    }
    val image = ThumbnailImageView(this)
    hero.addView(image, frameMatch())
    val placeholder = label(video.title.take(2).ifBlank { "影" }, 40f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Colors.SURFACE_ELEVATED, Colors.DEEP_SPACE),
        )
    }
    hero.addView(placeholder, frameMatch())
    hero.addView(
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(205, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.TRANSPARENT),
            )
        },
        frameMatch(),
    )
    hero.addView(
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, 11, 11, 11)),
            )
        },
        frameMatch(),
    )

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(32), dp(20), dp(32), dp(20))
    }
    content.addView(
        label("精选", 14f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            background = rounded(Colors.APPLE_BLUE, 6)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        },
        LinearLayout.LayoutParams(wrap(), wrap()),
    )
    content.addView(
        label(video.title, 28f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        },
        linearMatch().apply { topMargin = dp(10) },
    )
    videoMeta(video).takeIf { it.isNotBlank() }?.let { meta ->
        content.addView(
            label(meta, 15f, Colors.TEXT_SECONDARY, maxLines = 1),
            LinearLayout.LayoutParams(wrap(), wrap()).apply { topMargin = dp(6) },
        )
    }
    video.description?.takeIf { it.isNotBlank() }?.let { desc ->
        content.addView(
            label(desc, 14f, Colors.TEXT_SECONDARY, maxLines = 1),
            LinearLayout.LayoutParams(wrap(), wrap()).apply { topMargin = dp(4) },
        )
    }

    val play = primaryPill("播放", heightDp = 48, horizontalPaddingDp = 28) {
        viewModel.openDetails(video, autoPlay = true)
    }.apply {
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    requestHomeFocus(HomeFocusTarget.HeroDetails)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentState.currentProvider != "youtube") {
                        requestHomeFocus(HomeFocusTarget.Filter(currentState.durationFilter.id))
                    } else {
                        homeDefaultCategoryTarget(currentState)?.let { requestHomeFocus(it) }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    homeFocusArea = HomeFocusArea.Video
                    currentState.displayItems().firstOrNull()?.id
                        ?.let { requestHomeFocus(HomeFocusTarget.Video(it)) }
                    true
                }
                else -> false
            }
        }
    }
    val details = pill("详情", selected = false, heightDp = 48, horizontalPaddingDp = 24) {
        viewModel.openDetails(video)
    }.apply {
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    requestHomeFocus(HomeFocusTarget.HeroPlay)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    homeFocusArea = HomeFocusArea.Video
                    currentState.displayItems().firstOrNull()?.id
                        ?.let { requestHomeFocus(HomeFocusTarget.Video(it)) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentState.currentProvider != "youtube") {
                        requestHomeFocus(HomeFocusTarget.Filter(currentState.durationFilter.id))
                    } else {
                        homeDefaultCategoryTarget(currentState)?.let { requestHomeFocus(it) }
                    }
                    true
                }
                else -> false
            }
        }
    }
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    actions.addView(play, LinearLayout.LayoutParams(wrap(), dp(48)))
    actions.addView(details, LinearLayout.LayoutParams(wrap(), dp(48)).withStartMargin(dp(12)))
    content.addView(actions, LinearLayout.LayoutParams(wrap(), wrap()).apply { topMargin = dp(14) })

    hero.addView(
        content,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ),
    )
    registerFocusTarget(HomeFocusTarget.HeroPlay, play)
    registerFocusTarget(HomeFocusTarget.HeroDetails, details)
    loadImage(
        url = video.thumbnailUrl,
        headers = emptyMap(),
        image = image,
        placeholder = placeholder,
        title = video.title,
        maxWidthPx = resources.displayMetrics.widthPixels - dp(96),
        maxHeightPx = dp(230),
    )
    return hero
}

internal fun MainActivity.homeHeroVideo(state: com.example.mediacenter.ui.MediaUiState): VideoSummary? {
    if (state.query.isNotBlank()) return null
    val items = state.displayItems()
    // 与新版首页一致:优先有封面的条目,避免首屏大图位是一块灰色占位。
    return items.firstOrNull { !it.thumbnailUrl.isNullOrBlank() } ?: items.firstOrNull()
}
