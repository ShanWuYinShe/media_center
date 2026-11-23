package com.example.mediacenter.ui.details

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isNotEmpty
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.graphics.drawable.GradientDrawable
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.PlaybackOption
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
import com.example.mediacenter.ui.components.horizontalFocusStrip
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.formatDurationSeconds
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withTopMargin
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
            selectedQualityQn = null
            detailFocusTarget = DetailFocusTarget.Play
        }
        val screen = FrameLayout(this).apply { background = appBackground() }
        // 详情页电影感背景:用海报的重度降采样版本作为全屏模糊背景 + 多层 scrim,
        // 既呼应内容又保证右侧信息区文字可读(签名 URL 不落盘,仅内存渲染)。
        val backdrop = ThumbnailImageView(this).apply { alpha = 0f }
        screen.addView(backdrop, frameMatch())
        screen.addView(detailBackdropScrim(), frameMatch())
        // 详情页用服务端下发的大图;旧服务端不返回时回退卡片缩略图。
        // 背景层最终只解码到 48×48 放大模糊,用卡片尺寸图即可:它通常已在列表里
        // 下载/缓存过,避免与前景大图各下载解码一份大图(老盒子上是实打实的双份流量与解码)。
        loadBackdropImage(details.thumbnailUrl ?: details.thumbnailUrlLarge, emptyMap(), backdrop)
        // 详情页采用 Android TV / Material 设计规范的固定 dp/sp 值:
        // 标题 30sp(headline)、正文 18sp、说明 14sp(caption,均 ≥ TV 最小可读字号),
        // 主操作与可聚焦控件 ≥ 48dp 焦点目标,页边距满足 48/27dp overscan 安全区。
        // 这些固定值由 tvDensity 统一换算,因此在任意分辨率/上报 density 下都按比例缩放,
        // 既符合规范又能灵活适配所有设备,无需写死像素或临时比例。
        val hPad = dp(56)
        val vPad = dp(96)
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
        // 与新版一致:返回入口带文字，触摸/鼠标可直接点；遥控器仍有 BACK 键。
        val backButton = label("返回", 17f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = rounded(Colors.GlassFill, 999, 1, Colors.GlassBorder)
            isFocusable = false
            isClickable = true
            setOnClickListener { viewModel.back() }
        }
        screen.addView(
            backButton,
            FrameLayout.LayoutParams(wrap(), dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = hPad
                topMargin = dp(28)
            },
        )
        var detailFocusRegistered = false
        fun registerDetailFocus(target: DetailFocusTarget, view: View) {
            if (!detailFocusRegistered && detailFocusTarget == target) {
                detailFocusRegistered = true
                // 每次 render 都会重建 View,解析期还可能连续重建(loading 态更新)。
                // 单次 post 在视图尚未 attach/可见时 requestFocus 会静默失败,遥控器
                // 就整页失灵;这里有限重试(约 1.8s)直到真正拿到焦点。
                var remaining = 12
                val focusRunnable = object : Runnable {
                    override fun run() {
                        if (view.requestFocus()) return
                        if (remaining-- > 0 && view.isAttachedToWindow) {
                            view.postDelayed(this, 150)
                        }
                    }
                }
                view.post(focusRunnable)
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
        val heroImage = ThumbnailImageView(this).apply {
            // hero 是 22dp 圆角,图片必须自己裁圆角,否则方角图会盖住圆角背景。
            cornerRadiusPx = dp(22).toFloat()
        }
        val heroInitials = label(details.title.take(2).ifBlank { "影" }, 64f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
            gravity = Gravity.CENTER
            alpha = 1f
        }
        hero.addView(heroImage, frameMatch())
        hero.addView(heroInitials, frameMatch())
        loadImage(
            url = details.thumbnailUrlLarge ?: details.thumbnailUrl,
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
        // 描边画在最上层:图片会盖住 hero 背景上的 1dp 玻璃边框。
        hero.addView(
            View(this).apply { background = rounded(Color.TRANSPARENT, 22, 1, Colors.GlassBorder) },
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
            overScrollMode = View.OVER_SCROLL_NEVER
            // 滚动容器必须裁剪(旧实现 clipChildren=false,滚出视口的信息会画到
            // 96dp 留白区,像"内容漂浮在页边");用上下 padding + clipToPadding=false
            // 给焦点放大留余量。
            clipChildren = true
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(6))
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
        val descriptionText = details.description ?: ""
        val descLabel = label(
            descriptionText.takeIf { it.isNotBlank() } ?: "暂无简介",
            descSize,
            Colors.TEXT_SECONDARY,
            maxLines = descLines,
        ).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        info.addView(
            descLabel,
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapDesc)),
        )
        // 「查看完整简介」入口按**实际布局是否省略**决定,而不是字符数阈值:
        // 401dp 列宽下 3 行只能显示 ≈67 个汉字,旧阈值 96 会让 67~96 字的简介
        // 被静默截断且没有入口。先 GONE 加入,布局完成后再按末行 ellipsis 显示。
        val descriptionDisclosure = pill(
            "查看完整简介",
            selected = false,
            heightDp = chipH,
            horizontalPaddingDp = chipHPad,
        ) {
            showDescriptionDialog(details.title, descriptionText)
        }
        descriptionDisclosure.visibility = View.GONE
        info.addView(
            descriptionDisclosure,
            LinearLayout.LayoutParams(wrap(), dp(chipH)).withTopMargin(dp(gapDisclosure)),
        )
        descLabel.post {
            val textLayout = descLabel.layout ?: return@post
            val lastLine = descLabel.maxLines - 1
            val truncated = lastLine >= 0 &&
                textLayout.lineCount >= descLabel.maxLines &&
                textLayout.getEllipsisCount(lastLine) > 0
            if (truncated) {
                descriptionDisclosure.visibility = View.VISIBLE
            }
        }

        var detailErrorRetry: () -> Unit = viewModel::refreshSelectedPlayback
        if (options.isEmpty()) {
            // 播放地址解析中不要渲染「暂无可播放视频」:这不是错误,解析完成即消失,
            // 提前展示会误导用户。给一句明确的等待文案(YouTube 解析可达数十秒)。
            // 只有真正解析结束且没有可播地址时才进入错误态,并把焦点交给「刷新播放地址」。
            if (state.loading) {
                // 解析期(YouTube 冷启动可达数十秒)必须有一个可聚焦控件:旧实现只放
                // 一行不可聚焦文案,期间遥控器方向键/OK 全部失效,用户以为 App 卡死。
                // 按 OK 重新发起解析(ViewModel 会取消上一次请求,幂等)。
                val parsingPill = pill(
                    "正在解析播放地址…",
                    selected = false,
                    heightDp = chipH,
                    horizontalPaddingDp = chipHPad,
                    onFocusChange = { focused ->
                        if (focused) detailFocusTarget = DetailFocusTarget.Play
                    },
                    onClick = { viewModel.refreshSelectedPlayback() },
                )
                registerDetailFocus(DetailFocusTarget.Play, parsingPill)
                info.addView(
                    parsingPill,
                    LinearLayout.LayoutParams(wrap(), dp(chipH)).withTopMargin(dp(gapPlay)),
                )
                info.addView(
                    label("解析可能需要数十秒，按 OK 可重试", 13f, Colors.TEXT_TERTIARY, maxLines = 1),
                    LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(10)),
                )
            } else {
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
            }
        } else {
            // 解析成功会重建整个详情页:上一轮可能把焦点目标改成过 Refresh(空态),
            // 而 options 到达后 Refresh 已不存在。这里归位到播放按钮,否则整页
            // 重建后没有任何 requestFocus,遥控器方向键/OK 会全部失效。
            if (detailFocusTarget == DetailFocusTarget.Refresh) {
                detailFocusTarget = DetailFocusTarget.Play
            }
            val option = selectedOption!!
            selectedPlaybackOptionId = option.id
            detailErrorRetry = { viewModel.play(option) }
            val currentDuration = state.currentPages?.currentPageDurationSeconds ?: details.durationSeconds
            val durationText = currentDuration?.let { formatDurationSeconds(it) }
            // 用户选了清晰度 chip:用该 qn 合成播放 option。play() 发现 qn 与当前
            // 流不同时会按新 qn 重新解析 playurl;相同则直接用已缓存流,不重复请求。
            val selectedQuality = state.availableQualities.firstOrNull { it.qn == selectedQualityQn }
            val playOption = if (selectedQuality != null) {
                PlaybackOption(
                    id = "quality-${selectedQuality.qn}",
                    kind = "vod",
                    label = selectedQuality.label,
                    url = "",
                    qn = selectedQuality.qn,
                )
            } else {
                option
            }
            val playButton = primaryPill(
                // 只放「播放 + 时长」:401dp 的信息列放不下「播放 + 时长 + 清晰度」,
                // 单行省略会把清晰度吃掉;清晰度由下方 chips 高亮表达。
                text = buildString {
                    append("播放")
                    durationText?.let { append(" $it") }
                },
                heightDp = playH,
                horizontalPaddingDp = playHPad,
                textSizeSp = playTextSize,
                icon = PillIcon.Play,
                onFocusChange = { focused ->
                    if (focused) detailFocusTarget = DetailFocusTarget.Play
                },
                onClick = { viewModel.play(playOption) },
            )
            registerDetailFocus(DetailFocusTarget.Play, playButton)
            // 左右留 10dp:主按钮 1.05 倍聚焦放大(≈9.5dp)不会被 infoScroll 视口裁掉。
            info.addView(
                playButton,
                LinearLayout.LayoutParams(match(), dp(playH)).apply {
                    leftMargin = dp(10)
                    rightMargin = dp(10)
                    topMargin = dp(gapPlay)
                },
            )

            // 清晰度 chips:优先用 playurl 返回的可用清晰度列表(availableQualities,
            // 之前恒返回单选项导致这里永不渲染);兼容非 B 站 provider 的多选项
            // playbackOptions(此时 availableQualities 为空)。
            val qualityChips = mutableListOf<View>()
            val qualities = state.availableQualities
            if (qualities.size > 1) {
                qualities.take(5).forEach { quality ->
                    val chip = pill(
                        text = quality.label.ifBlank { "播放" },
                        selected = quality.qn == selectedQualityQn,
                        heightDp = chipH,
                        horizontalPaddingDp = chipHPad,
                        onFocusChange = { focused ->
                            if (focused) detailFocusTarget = DetailFocusTarget.Option("quality-${quality.qn}")
                        },
                        onClick = {
                            selectedQualityQn = quality.qn
                            detailFocusTarget = DetailFocusTarget.Option("quality-${quality.qn}")
                            invalidateUi()
                        },
                    )
                    registerDetailFocus(DetailFocusTarget.Option("quality-${quality.qn}"), chip)
                    qualityChips.add(chip)
                }
            } else if (options.size > 1) {
                options.take(5).forEach { optionItem ->
                    val chip = pill(
                        text = optionItem.label.ifBlank { "播放" },
                        selected = optionItem.id == selectedOption.id,
                        heightDp = chipH,
                        horizontalPaddingDp = chipHPad,
                        onFocusChange = { focused ->
                            if (focused) detailFocusTarget = DetailFocusTarget.Option(optionItem.id)
                        },
                        onClick = {
                            selectedPlaybackOptionId = optionItem.id
                            selectedQualityQn = null
                            detailFocusTarget = DetailFocusTarget.Option(optionItem.id)
                            invalidateUi()
                        },
                    )
                    registerDetailFocus(DetailFocusTarget.Option(optionItem.id), chip)
                    qualityChips.add(chip)
                }
            }
            if (qualityChips.isNotEmpty()) {
                info.addView(label("清晰度", metaSize, Colors.TEXT_TERTIARY, bold = true, maxLines = 1), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(gapQuality)))
                val optionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(0, dp(gapDisclosure), 0, dp(4))
                }
                qualityChips.forEach { chip ->
                    optionRow.addView(
                        chip,
                        LinearLayout.LayoutParams(wrap(), dp(chipH)).withEndMargin(dp(14)),
                    )
                }
                // 401dp 信息列放不下 4~5 档清晰度:B 站 1080P 视频通常 4~5 档,
                // 旧实现 LinearLayout 会把超出宽度的 chip 压成 0 宽(只剩空胶囊,
                // 仍可聚焦)。改为横向焦点条,支持滚动且放大不被裁。
                info.addView(horizontalFocusStrip(optionRow, padH = 6, padV = 0), linearMatch())
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
                        info.addView(horizontalFocusStrip(pageRow, padH = 6, padV = 0), linearMatch())
                    }
                }
            }
        }

        state.error?.let {
            info.addView(errorInline(it, detailErrorRetry), LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(24)))
        }

        // 解析播放地址期间信息区已有「正在解析播放地址…」可聚焦胶囊(见 options.isEmpty()
        // 分支),再叠一个居中 loading 遮罩会和它重叠成两段文字(实测「正在加载」压在
        // 「正在解析播放地址…」上)。只有已有播放选项、正在刷新时才用居中遮罩。
        if (state.loading && options.isNotEmpty()) {
            screen.addView(loadingScrim(), FrameLayout.LayoutParams(wrap(), wrap(), Gravity.CENTER))
        }
        setContent(screen)
    }

