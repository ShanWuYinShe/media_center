package com.example.mediacenter.ui

import android.app.ActivityManager
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ScrollView
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.widget.Toast
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.example.mediacenter.BuildConfig
import com.example.mediacenter.net.AppHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.data.ProviderInfo
import com.example.mediacenter.data.SearchHistory
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.WatchHistory
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.roundedVisibleStroke
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.ui.theme.HomeFocusArea
import com.example.mediacenter.update.AppUpdateResult
import com.example.mediacenter.update.InstallLaunchResult

// ── 弹窗 overscan 安全区 ──
// 老 TV/盒子会裁掉画面边缘(overscan)。应用内页面已统一预留 56/40dp 安全区,
// 但弹窗按 displayMetrics 全幅取比例会顶到物理边缘,边框/关闭按钮可能被切。
// 统一在此扣除水平 48dp / 垂直 27dp 的双倍(两侧)安全边距。
private const val DIALOG_SAFE_MARGIN_X_DP = 48
private const val DIALOG_SAFE_MARGIN_Y_DP = 27

/** 按 overscan 安全区收缩后的比例设置弹窗窗口尺寸;高度传 null 表示 wrap。 */
internal fun Dialog.applySafeDialogLayout(
    widthFraction: Float,
    heightFraction: Float? = null,
) {
    val dm = context.resources.displayMetrics
    val density = (context as? MainActivity)?.tvDensity ?: dm.density
    val safeX = (DIALOG_SAFE_MARGIN_X_DP * density * 2).toInt()
    val safeY = (DIALOG_SAFE_MARGIN_Y_DP * density * 2).toInt()
    window?.setLayout(
        ((dm.widthPixels - safeX) * widthFraction).toInt(),
        if (heightFraction != null) ((dm.heightPixels - safeY) * heightFraction).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

internal fun MainActivity.showDescriptionDialog(title: String, description: String) {
        val activity = this
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label(title, 24f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2).apply {
                    letterSpacingCompat(-0.01f)
                },
                linearMatch(),
            )
            val scroll = ScrollView(activity)
            scroll.addView(
                label(description, 17f, Colors.TEXT_SECONDARY, maxLines = Int.MAX_VALUE).apply {
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
            applySafeDialogLayout(0.62f, 0.72f)
            close.post { close.requestFocus() }
        }.show()
    }

    /**
     * 系统诊断信息弹窗(最左分类连续按左键 5 次触发)。
     * 展示电视真实系统环境——用于戳穿国产盒子
     * "显示 4.4.2 但内核实为 5.0+"的魔改、定位网络/TLS/内存等问题的根因。
     * 视觉与布局完全复用 [showDescriptionDialog] 的模式。
     */
internal fun MainActivity.showSystemInfoDialog() {
        val activity = this
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("系统诊断信息", 24f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1).apply {
                    letterSpacingCompat(-0.01f)
                },
                linearMatch(),
            )
            card.addView(
                label("焦点在最左分类时连续按左键 5 次触发 · 仅用于排查", 13f, Colors.TEXT_TERTIARY, maxLines = 1),
                LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(4)),
            )
            // 诊断信息行数多(14+ 行),1080p TV 上远超 0.72 倍屏高的弹窗内容区:
            // 必须放进 ScrollView,否则底部「播放缓存占用/清除缓存/关闭」会被裁掉、
            // 用户以为弹窗没有这些入口(焦点还会请求到窗口外的关闭按钮上)。
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val dm = resources.displayMetrics
            val memInfo = ActivityManager.MemoryInfo().also {
                (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
            }
            val serverHost = try {
                java.net.URI(BuildConfig.MEDIA_SERVER_URL).host
            } catch (_: Exception) {
                BuildConfig.MEDIA_SERVER_URL
            }
            val availMemMb = if (memInfo.availMem > 0) memInfo.availMem / (1024 * 1024) else -1
            val totalMemMb = if (memInfo.totalMem > 0) memInfo.totalMem / (1024 * 1024) else -1

            // 信息行:左 label(灰)+ 右 value(白),SDK_INT 用 AppleBlue 突出(最关键)。
            // 返回 value 视图,供异步计算的值(如缓存占用)回填。
            fun row(title: String, value: String, highlight: Boolean = false): TextView {
                val rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowLayout.addView(
                    label("$title：", 16f, Colors.TEXT_TERTIARY, maxLines = 1),
                    LinearLayout.LayoutParams(0, wrap(), 0.42f),
                )
                val valueColor = if (highlight) Colors.APPLE_BLUE else Colors.TEXT_PRIMARY
                val valueView = label(value, 16f, valueColor, maxLines = 3)
                rowLayout.addView(
                    valueView,
                    LinearLayout.LayoutParams(0, wrap(), 0.58f),
                )
                content.addView(rowLayout, linearMatch().withTopMargin(dp(10)))
                return valueView
            }

            // CPU 架构:Build.SUPPORTED_ABIS 是 API 21+,API 19 用废弃的 CPU_ABI/CPU_ABI2。
            @Suppress("DEPRECATION")
            val cpuAbi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.joinToString(", ")
            } else {
                listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotEmpty() }.joinToString(", ")
            }
            row("系统 API 级别", "${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})", highlight = true)
            row("厂商", Build.MANUFACTURER)
            row("型号", "${Build.MODEL} / ${Build.DEVICE}")
            row("CPU 架构", cpuAbi)
            row("屏幕分辨率", "${dm.widthPixels}×${dm.heightPixels}")
            row("屏幕密度", "${dm.densityDpi}dpi (×${dm.density})")
            row("App 版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            row("服务端地址", serverHost)
            val prefIpRow = row("网络加速", com.example.mediacenter.net.PreferredIpStore.statusText())
            // 连接模式:HTTPS 握手失败自动降级明文 HTTP 后显示,便于排查 TLS 兼容性。
            row("连接模式", if (viewModel.httpFallbackActive) "明文 HTTP(已降级)" else "HTTPS", highlight = viewModel.httpFallbackActive)
            // TLS 引擎与校验降级计数:Conscrypt 主路径 / 平台回退,以及证书校验
            // soft-fail 接受连接的次数。与"连接模式"一起构成网络链路诊断三件套。
            row("TLS 引擎", AppHttpClient.tlsPathLabel, highlight = AppHttpClient.tlsPathLabel != "Conscrypt")
            row("TLS 校验降级", "${AppHttpClient.softFailCount} 次", highlight = AppHttpClient.softFailCount > 0)
            // 排查"检查更新失败 / 检查系统时间"类问题最关键的现场:
            // 设备当前时间(证书时间类失败的确定性依据)与最近一次错误文案。
            row("系统时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            row("最近错误", currentState.error ?: "无")
            row("可用内存", "${availMemMb}MB / ${totalMemMb}MB")

            // 播放媒体缓存占用与清除:老盒子磁盘紧张时的自救入口。
            // 缓存大小与清理都是磁盘 IO(可能数秒),不能在主线程做,否则打开
            // 诊断弹窗/点清除时界面直接卡住;先显示「计算中…」再异步回填。
            val cacheSizeValue = row("播放缓存占用", "计算中…")
            val cacheScope = this@showSystemInfoDialog.lifecycleScope
            cacheScope.launch {
                val cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.mediacenter.player.LegacyPlaybackMediaCache
                        .cacheSizeBytes(this@showSystemInfoDialog)
                }
                val mb = cacheBytes / (1024 * 1024)
                cacheSizeValue.text = if (cacheBytes >= 0) "${mb}MB" else "不可用"
            }

            val scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                // 滚动容器必须裁剪:旧实现 clipChildren=false 时,滚出视口的
                // 信息行会画到卡片 padding 区、压过副标题与底部按钮。
                clipChildren = true
                // 上下 padding + clipToPadding=false 给内容一点呼吸空间。
                clipToPadding = false
                setPadding(0, dp(6), 0, dp(6))
                addView(content, ViewGroup.LayoutParams(match(), wrap()))
            }
            card.addView(scroll, LinearLayout.LayoutParams(match(), 0, 1f).withTopMargin(dp(8)))
            // 弹窗里只有按钮可聚焦,ScrollView 本身吃不到遥控器按键:
            // 在按钮上拦截上/下键滚动内容。到了滚动边界必须把按键还给系统,
            // 否则「清除媒体缓存」按钮永远吞掉 DPAD_UP/DOWN,遥控器无法在
            // 两个按钮之间移动(只能点关闭)。
            val scrollKeyListener = View.OnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN ->
                        if (scroll.canScrollVertically(1)) {
                            scroll.smoothScrollBy(0, dp(180))
                            true
                        } else {
                            false
                        }
                    KeyEvent.KEYCODE_DPAD_UP ->
                        if (scroll.canScrollVertically(-1)) {
                            scroll.smoothScrollBy(0, -dp(180))
                            true
                        } else {
                            false
                        }
                    else -> false
                }
            }

            var clearBtn: TextView = TextView(activity)
            clearBtn = pill(
                text = "清除媒体缓存",
                selected = false,
                heightDp = 48,
                horizontalPaddingDp = 28,
                onClick = {
                    // 逐资源移除是磁盘 IO:置灰按钮防重复点击,放到 IO 线程执行,
                    // 完成后回主线程给结果反馈(老盒子上 128MB 缓存删除可耗数百 ms)。
                    val btn = clearBtn
                    if (!btn.isEnabled) {
                        // 正在清除中,忽略重复点击。
                    } else {
                        btn.isEnabled = false
                        btn.text = "正在清除…"
                        cacheScope.launch {
                        val removed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.mediacenter.player.LegacyPlaybackMediaCache
                                .clearAll(this@showSystemInfoDialog)
                        }
                        btn.isEnabled = true
                        btn.text = when {
                            removed < 0 -> "清除失败，请重试"
                            removed == 0 -> "缓存已是空的"
                            else -> "已清除 $removed 项"
                        }
                        if (removed > 0) cacheSizeValue.text = "0MB"
                        }
                    }
                },
            )
            // 底部操作行:三个按钮横向排列(与「发现新版本」「更新失败」弹窗一致)。
            // 诊断信息行数多,纵向叠三个按钮会把可视信息压到只剩一两行;横向一行
            // 不额外占高度,内容区可视行数与加按钮之前保持一致。
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            clearBtn.setOnKeyListener(scrollKeyListener)
            actions.addView(clearBtn, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(12)))

            var speedTestBtn: TextView = TextView(activity)
            speedTestBtn = pill(
                text = "网络测速",
                selected = false,
                heightDp = 48,
                horizontalPaddingDp = 22,
                onClick = {
                    val btn = speedTestBtn
                    if (btn.isEnabled) {
                        btn.isEnabled = false
                        btn.text = "正在测速…"
                        cacheScope.launch {
                            val newStatus = com.example.mediacenter.net.PreferredIpStore.retest()
                            btn.isEnabled = true
                            btn.text = "网络测速"
                            prefIpRow.text = newStatus
                            Toast.makeText(this@showSystemInfoDialog, "测速完成", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            speedTestBtn.setOnKeyListener(scrollKeyListener)
            actions.addView(speedTestBtn, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(12)))

            val clearHistoryBtn = pill(
                text = "清空历史",
                selected = false,
                heightDp = 48,
                horizontalPaddingDp = 24,
                onClick = {
                    WatchHistory.clear(this@showSystemInfoDialog)
                    SearchHistory.clear(this@showSystemInfoDialog)
                    viewModel.refreshWatchHistory()
                    Toast.makeText(this@showSystemInfoDialog, "观看与搜索历史已清空", Toast.LENGTH_SHORT).show()
                },
            )
            clearHistoryBtn.setOnKeyListener(scrollKeyListener)
            actions.addView(clearHistoryBtn, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(12)))

            // 「检查更新」:手动检查入口(自动检查失败/系统时间等问题的现场排查配套)。
            // 必须先关诊断弹窗再执行——检查结果/安装提示是独立弹窗,叠加会互相
            // 抢遥控器焦点;root.post 保证在弹窗真正 dismiss 之后再发起。
            val checkUpdate = pill(
                text = "检查更新",
                selected = false,
                heightDp = 48,
                horizontalPaddingDp = 28,
                onClick = {
                    dismiss()
                    root.post { if (!isFinishing) checkUpdateManually() }
                },
            )
            checkUpdate.setOnKeyListener(scrollKeyListener)
            actions.addView(checkUpdate, LinearLayout.LayoutParams(wrap(), dp(48)).withEndMargin(dp(12)))

            val close = pill("关闭", selected = true, heightDp = 48, horizontalPaddingDp = 28) { dismiss() }
            close.setOnKeyListener(scrollKeyListener)
            actions.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)))
            card.addView(actions, LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            applySafeDialogLayout(0.62f, 0.72f)
            close.post { close.requestFocus() }
        }.show()
    }

internal fun MainActivity.showAllPagesDialog(pages: PlaybackPages) {
    val titles = List(pages.totalPages) { i ->
        episodeDisplayTitle(i, pages.pageTitles.getOrElse(i) { "" })
    }
    showOptionListDialog(
        title = "选集 · 共${pages.totalPages}集",
        options = titles,
        currentIndex = pages.currentPage,
        // 文案已含「第 N 集」,不再显示编号列(否则出现「1 第 1 集」重复)
        showIndex = false,
    ) { index -> viewModel.selectPage(index) }
}

/**
 * 通用单列选择弹窗(选集 / 清晰度等共用)。
 *
 * 用 RecyclerView 惰性建行:选集动辄几百集,ScrollView+LinearLayout 一次性全量
 * inflate 在 4.4.2 老盒子上打开即卡。行样式与旧实现一致(焦点态 AppleBlue 背景 +
 * 白色文字 + 微放大,current 项淡蓝底色),编号列固定宽度保持对齐。
 *
 * @param options 每行的显示文本
 * @param currentIndex 当前选中项(淡蓝底色高亮),null 表示无高亮
 * @param onSelect 选中回调(传行号);null 表示不可选(纯展示)
 */
internal fun MainActivity.showOptionListDialog(
    title: String,
    options: List<String>,
    currentIndex: Int?,
    // 选集等"文案本身已含第 N 集"的场景关掉编号列,避免出现「1  第 1 集」重复;
    // 清晰度/视频源等纯文本选项保持编号列(默认 true,既有调用行为不变)。
    // 注意放在 onSelect 之前:既有调用都以尾随 lambda 传 onSelect,尾随 lambda
    // 只能绑定最后一个参数。
    showIndex: Boolean = true,
    onSelect: ((Int) -> Unit)?,
) {
    val activity = this
    Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
        }
        card.addView(label(title, 22f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1), linearMatch())

        // 行内状态(绑定期更新):index 供点击回调,isCurrent 供焦点/底色刷新。
        class OptionRowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private var boundIndex = 0
            private var boundIsCurrent = false
            private val idxLabel = label("", 13f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
                gravity = Gravity.CENTER
                minWidth = dp(36)
            }
            // 选集行是「第 N 集 · 原标题」,单行会把原标题整段吃掉,允许两行。
            private val titleLabel = label("", 15f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2)

            init {
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(4), dp(8), dp(4), dp(8))
                row.isFocusable = true
                row.isClickable = true
                if (showIndex) {
                    row.addView(idxLabel, LinearLayout.LayoutParams(dp(36), wrap()))
                    titleLabel.layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).withStartMargin(dp(8))
                } else {
                    idxLabel.visibility = View.GONE
                    titleLabel.layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
                }
                row.addView(titleLabel)
                row.setOnClickListener {
                    if (onSelect != null) {
                        dismiss()
                        onSelect(boundIndex)
                    }
                }
                // 焦点态:AppleBlue 背景 + 白色文字 + 微放大,与 pill 风格一致
                row.setOnFocusChangeListener { _, focused ->
                    row.background = if (focused) {
                        // 行是整条宽块,2dp 白框显得厚重;1.5dp 细描边 + 蓝底足够。
                        roundedVisibleStroke(Colors.APPLE_BLUE, 12, 1.5f, Colors.GLASS_BORDER_FOCUSED)
                    } else {
                        rounded(
                            if (boundIsCurrent) ColorUtils.setAlphaComponent(Colors.APPLE_BLUE, 38)
                            else Color.TRANSPARENT,
                            12,
                            0,
                            Color.TRANSPARENT,
                        )
                    }
                    row.elevationCompat(if (focused) dp(6).toFloat() else 0f)
                    idxLabel.setTextColor(if (focused) Colors.TEXT_PRIMARY else Colors.TEXT_TERTIARY)
                    titleLabel.setTextColor(
                        if (focused) Colors.TEXT_PRIMARY
                        else if (boundIsCurrent) Colors.APPLE_BLUE
                        else Colors.TEXT_PRIMARY,
                    )
                    row.animate().cancel()
                    row.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(140).start()
                }
            }

            fun bind(index: Int) {
                boundIndex = index
                boundIsCurrent = index == currentIndex
                if (showIndex) {
                    idxLabel.text = String.format(Locale.getDefault(), "%d", index + 1)
                }
                titleLabel.text = options[index]
                titleLabel.isSelected = false
                // 非聚焦行按 current 态设置底色:旧实现只处理「非 current」,
                // 新建行背景为 null → 首次打开时当前项没有淡蓝底,看不出当前值。
                if (!row.isFocused) {
                    row.background = if (boundIsCurrent) {
                        rounded(ColorUtils.setAlphaComponent(Colors.APPLE_BLUE, 38), 12, 0, Color.TRANSPARENT)
                    } else {
                        rounded(Color.TRANSPARENT, 12, 0, Color.TRANSPARENT)
                    }
                }
            }
        }

        val listAdapter = object : RecyclerView.Adapter<OptionRowHolder>() {
            override fun getItemCount(): Int = options.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionRowHolder {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // 显式 match 宽度:整行高亮是 TV 列表的焦点范式,也让标题的
                    // weight=1 真正填满;否则行宽退化成 wrap,焦点框只有一小条。
                    layoutParams = RecyclerView.LayoutParams(match(), wrap())
                }
                return OptionRowHolder(row)
            }

            override fun onBindViewHolder(holder: OptionRowHolder, position: Int) {
                holder.bind(position)
            }
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = listAdapter
            // 行聚焦会放大 1.04 倍:旧实现 RV 无 padding + clipChildren=true,
            // 左右各 ≈10dp 焦点框被裁,只剩上下两条边。12dp 侧 padding 覆盖
            // 放大溢出,clipToPadding=false 允许画进 padding 区。
            setPadding(dp(12), dp(6), dp(12), dp(6))
            clipToPadding = false
        }
        card.addView(recycler, LinearLayout.LayoutParams(match(), 0, 1f).withTopMargin(dp(12)))
        val close = primaryPill("关闭", heightDp = 48, horizontalPaddingDp = 28) { dismiss() }
        card.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(16)))
        setContentView(card)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applySafeDialogLayout(0.62f, 0.72f)
        // 焦点落到当前项;无 current 则落到关闭按钮。
        // scrollToPosition 后布局未执行,屏外项 findViewHolder 返回 null——
        // 长列表(200+ 集选集正是用 RV 的场景)会静默退化成焦点落关闭按钮。
        // 改为 scrollToPositionWithOffset 预定位 + 布局完成后二次重试聚焦。
        val focusIndex = currentIndex ?: -1
        if (focusIndex >= 0) {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(focusIndex, recycler.height / 3)
        }
        // RecyclerView 首次布局完成后再聚焦当前项:只 post 一两次时 dialog
        // 窗口尚未 attach/布局,findViewHolder 恒为 null,会退化成聚焦“关闭”。
        // 老盒子/Dialog 窗口布局可能晚于固定延迟:轮询到行真正 attach 后再聚焦,
        // 最多约 1.5s;仍失败才回退到关闭按钮。直接 postDelayed 一次会在
        // findViewHolder 恒为 null 时静默退化成聚焦“关闭”。
        fun focusCurrentRow(attempt: Int = 0) {
            if (focusIndex < 0) {
                close.requestFocus()
                return
            }
            val vh = recycler.findViewHolderForAdapterPosition(focusIndex)
            if (vh != null && vh.itemView.requestFocus()) return
            if (attempt < 15) {
                recycler.postDelayed({ focusCurrentRow(attempt + 1) }, 100)
            } else {
                close.requestFocus()
            }
        }
        recycler.doOnLayout { focusCurrentRow() }
        setOnShowListener { recycler.postDelayed({ focusCurrentRow() }, 120) }
    }.show()
}

/** 视频源显示名:B站/YouTube;未知源回退 id。 */
internal fun providerDisplayName(provider: ProviderInfo, currentProvider: String? = null): String {
    val name = provider.name?.takeIf { it.isNotBlank() } ?: provider.id
    return if (provider.id == currentProvider) "$name ✓" else name
}

/** 视频源切换弹窗;选中后由 ViewModel 清空缓存并重新加载分类。 */
internal fun MainActivity.showProviderDialog(
    providers: List<ProviderInfo>,
    currentProvider: String,
) {
    if (providers.isEmpty()) return
    val currentIndex = providers.indexOfFirst { it.id == currentProvider }.takeIf { it >= 0 }
    showOptionListDialog(
        title = "选择视频源",
        options = providers.map { provider ->
            val name = provider.name?.takeIf { it.isNotBlank() } ?: provider.id
            if (provider.id == currentProvider) "$name（当前）" else name
        },
        currentIndex = currentIndex,
        onSelect = { index ->
            val provider = providers.getOrNull(index)?.id ?: return@showOptionListDialog
            if (provider == currentProvider) return@showOptionListDialog
            homeFocusArea = HomeFocusArea.Category
            pendingHomeFocus = null
            rememberedCategoryId = null
            viewModel.selectProvider(provider)
        },
    )
}

internal fun MainActivity.promptInstallUpdate(result: AppUpdateResult.ReadyToInstall) {
        val activity = this
        pendingUpdateApk = result.apk
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("发现新版本 ${result.update.versionName}", 22f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2),
                linearMatch(),
            )
            val notes = result.update.releaseNotes?.takeIf { it.isNotBlank() } ?: "已下载更新包，安装后可继续使用最新版本。"
            card.addView(
                // 上游 releaseNotes 上限 200 字符(见 release-app.yml):5 行只够
                // ≈110 字,再多会被省略;放宽到 10 行,弹窗高度 wrap 不会裁按钮。
                label(notes, 16f, Colors.TEXT_SECONDARY, maxLines = 10).apply {
                    setLineSpacing(dp(4).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(12)),
            )
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val install = primaryPill("安装更新", heightDp = 48, horizontalPaddingDp = 28) {
                dismiss()
                // 安装包含 APK 磁盘复制（PackageInstaller session 写入），
                // 放到 IO 线程执行，避免主线程卡顿。
                lifecycleScope.launch {
                    handleInstallLaunch(appUpdateManager.launchInstaller(result.apk), result.apk)
                }
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
            applySafeDialogLayout(0.48f)
            install.post { install.requestFocus() }
        }.show()
    }

internal fun MainActivity.handleInstallLaunch(result: InstallLaunchResult, apk: File) {
        val activity = this
        when (result) {
            InstallLaunchResult.Started -> {
                pendingUpdateApk = null
            }
            InstallLaunchResult.NeedsPermission -> {
                // API 26+ 需"安装未知应用"权限:引导去系统设置开启,onResume 时自动重试。
                // 保留 pendingUpdateApk,用户从设置页返回后 handleInstallLaunch 会再走一次。
                showUpdateMessage("安装更新需要先允许“安装未知应用”权限")
                appUpdateManager.launchInstallPermissionSettings()
            }
            is InstallLaunchResult.Failed -> showUpdateMessage(result.message)
        }
    }

internal fun MainActivity.showUpdateMessage(message: String) {
        val activity = this
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                background = rounded(Colors.SURFACE_ELEVATED, 22, 1, Colors.GlassBorder)
            }
            card.addView(label(message, 17f, Colors.TEXT_SECONDARY, maxLines = 6), linearMatch())
            val close = pill("知道了", selected = true, heightDp = 44, horizontalPaddingDp = 24) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(44)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            applySafeDialogLayout(0.42f)
            close.post { close.requestFocus() }
        }.show()
    }

/**
 * 更新失败弹窗:下载/安装任一阶段失败时给具体原因 + 「重试」/「知道了」。
 * 旧实现安装阶段失败只写日志、界面无反馈,用户以为"点了没反应",与 normal 端修复对齐。
 */
internal fun MainActivity.showUpdateFailureDialog(message: String) {
        val activity = this
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                background = rounded(Colors.SURFACE_ELEVATED, 22, 1, Colors.GlassBorder)
            }
            card.addView(label(message, 17f, Colors.TEXT_SECONDARY, maxLines = 6), linearMatch())
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val retry = primaryPill("重试", heightDp = 44, horizontalPaddingDp = 24) {
                dismiss()
                retryUpdateCheck()
            }
            val close = pill("知道了", selected = false, heightDp = 44, horizontalPaddingDp = 24) { dismiss() }
            actions.addView(retry, LinearLayout.LayoutParams(wrap(), dp(44)).withEndMargin(dp(12)))
            actions.addView(close, LinearLayout.LayoutParams(wrap(), dp(44)))
            card.addView(actions, LinearLayout.LayoutParams(wrap(), wrap()).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            applySafeDialogLayout(0.42f)
            retry.post { retry.requestFocus() }
        }.show()
    }

internal fun MainActivity.showWatchHistoryManageDialog(
    video: VideoSummary,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    val activity = this
    Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
            clipChildren = false
            clipToPadding = false
        }
        card.addView(label(video.title, 22f, Colors.TEXT_PRIMARY, bold = true, maxLines = 2))
        card.addView(
            label("管理该视频的观看记录与进度：", 16f, Colors.TEXT_SECONDARY, maxLines = 1),
            LinearLayout.LayoutParams(match(), wrap()).withTopMargin(dp(10)),
        )
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val removeBtn = primaryPill("从继续观看中移除", heightDp = 48, horizontalPaddingDp = 24) {
            dismiss()
            onRemove()
        }
        val restartBtn = pill("重新从头播放", selected = false, heightDp = 48, horizontalPaddingDp = 24) {
            dismiss()
            onRestart()
        }
        val cancelBtn = pill("取消", selected = false, heightDp = 48, horizontalPaddingDp = 24) {
            dismiss()
        }
        actions.addView(removeBtn, LinearLayout.LayoutParams(match(), dp(48)).withTopMargin(dp(16)))
        actions.addView(restartBtn, LinearLayout.LayoutParams(match(), dp(48)).withTopMargin(dp(10)))
        actions.addView(cancelBtn, LinearLayout.LayoutParams(match(), dp(48)).withTopMargin(dp(10)))
        card.addView(actions, linearMatch())
        setContentView(card)
        applySafeDialogLayout(0.46f)
        setOnShowListener {
            removeBtn.requestFocus()
        }
        show()
    }
}
