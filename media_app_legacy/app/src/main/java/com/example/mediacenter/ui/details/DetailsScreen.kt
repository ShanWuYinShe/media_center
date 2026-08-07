package com.example.mediacenter.ui.details

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isNotEmpty
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.VideoDetails
import com.example.mediacenter.ui.MediaUiState
import com.example.mediacenter.ui.setContent
import com.example.mediacenter.ui.showAllPagesDialog
import com.example.mediacenter.ui.showDescriptionDialog
import com.example.mediacenter.ui.components.appBackground
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.roundedGradient
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.detailBackdropScrim
import com.example.mediacenter.ui.components.errorInline
import com.example.mediacenter.ui.components.loadingScrim
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.formatDurationSeconds
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.videoMeta
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.config.CategorySelectDebounceMs
import com.example.mediacenter.ui.loadBackdropImage
import com.example.mediacenter.ui.loadImage
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.DetailFocusTarget
import com.example.mediacenter.ui.views.ThumbnailImageView
import com.example.mediacenter.ui.views.PillIcon

internal fun MainActivity.renderDetails(state: MediaUiState) {
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
        val gapDesc = 14
        val gapDisclosure = 10
        val gapPlay = 22
        val gapQuality = 18
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
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
        val heroInitials = label(details.title.take(2).ifBlank { "影" }, 64f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
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
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val infoScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = 2 // OVERSCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        infoScroll.addView(info, ViewGroup.LayoutParams(match(), wrap()))
        row.addView(infoScroll, LinearLayout.LayoutParams(0, match(), 1f))

        val options = details.playbackOptions
        val selectedOption = if (options.isNotEmpty()) {
            options.firstOrNull { it.id == selectedPlaybackOptionId } ?: options.first()
        } else null

        info.addView(
            label(details.title, titleSize, Colors.TEXT_PRIMARY, bold = true, maxLines = 2).apply {
                letterSpacingCompat(-0.01f)
            },
            linearMatch(),
        )
        info.addView(
            label(details.description?.takeIf { it.isNotBlank() } ?: "暂无简介", descSize, Colors.TEXT_SECONDARY, maxLines = descLines).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapDesc)),
        )

        val descriptionText = details.description ?: ""
        if (descriptionText.length > 96) {
            info.addView(
                pill("查看完整简介", selected = false, heightDp = chipH, horizontalPaddingDp = chipHPad) {
                    showDescriptionDialog(details.title, descriptionText)
                },
                LinearLayout.LayoutParams(wrap(), dp(chipH)).withTopMargin(dp(gapDisclosure)),
            )
        }

        var detailErrorRetry: () -> Unit = viewModel::refreshSelectedPlayback
        if (options.isEmpty()) {
            info.addView(label("暂无可播放视频", 15f, Colors.TEXT_TERTIARY, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapPlay)))
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
            val option = selectedOption!!
            selectedPlaybackOptionId = option.id
            detailErrorRetry = { viewModel.play(option) }
            val currentDuration = state.currentPages?.currentPageDurationSeconds ?: details.durationSeconds
            val durationText = currentDuration?.let { formatDurationSeconds(it) }
            val playButton = primaryPill(
                text = buildString {
                    append("播放")
                    durationText?.let { append(" $it") }
                    option.label.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                heightDp = playH,
                horizontalPaddingDp = playHPad,
                textSizeSp = playTextSize,
                icon = PillIcon.Play,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Play
                },
                onClick = { viewModel.play(option) },
            )
            registerDetailFocus(DetailFocusTarget.Play, playButton)
            info.addView(playButton, LinearLayout.LayoutParams(wrap(), dp(playH)).withTopMargin(dp(gapPlay)))

            if (options.size > 1) {
                info.addView(label("清晰度", metaSize, Colors.TEXT_TERTIARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapQuality)))
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

            // 多P选集:分P数量多时用导航+弹窗替代水平一排按钮
            state.currentPages?.let { pages ->
                if (pages.totalPages > 1) {
                    info.addView(label("选集 · 第${pages.currentPage + 1}/${pages.totalPages}集", metaSize, Colors.TEXT_TERTIARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapQuality)))
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
                        showAllPagesDialog(pages)
                    }
                    pageRow.addView(allChip, LinearLayout.LayoutParams(wrap(), dp(chipH)))
                    if (pageRow.isNotEmpty()) {
                        info.addView(pageRow, linearMatch())
                    }
                }
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

