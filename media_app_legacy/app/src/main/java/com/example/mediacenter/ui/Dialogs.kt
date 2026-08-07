package com.example.mediacenter.ui

import android.app.ActivityManager
import android.view.KeyEvent
import android.widget.ScrollView
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.mediacenter.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.mediacenter.MainActivity
import com.example.mediacenter.data.PlaybackPages
import com.example.mediacenter.ui.components.dp
import com.example.mediacenter.ui.components.elevationCompat
import com.example.mediacenter.ui.components.frameMatch
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.label
import com.example.mediacenter.ui.components.letterSpacingCompat
import com.example.mediacenter.ui.components.match
import com.example.mediacenter.ui.components.linearMatch
import com.example.mediacenter.ui.components.pill
import com.example.mediacenter.ui.components.primaryPill
import com.example.mediacenter.ui.components.rounded
import com.example.mediacenter.ui.components.wrap
import com.example.mediacenter.ui.components.withStartMargin
import com.example.mediacenter.ui.components.withEndMargin
import com.example.mediacenter.ui.components.withTopMargin
import com.example.mediacenter.ui.theme.Colors
import com.example.mediacenter.update.AppUpdateResult
import com.example.mediacenter.update.AppUpdateManager
import com.example.mediacenter.update.InstallLaunchResult

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
            window?.setLayout((resources.displayMetrics.widthPixels * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
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
            fun row(title: String, value: String, highlight: Boolean = false) {
                val rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowLayout.addView(
                    label("$title：", 16f, Colors.TEXT_TERTIARY, maxLines = 1),
                    LinearLayout.LayoutParams(0, wrap(), 0.42f),
                )
                val valueColor = if (highlight) Colors.APPLE_BLUE else Colors.TEXT_PRIMARY
                rowLayout.addView(
                    label(value, 16f, valueColor, maxLines = 2),
                    LinearLayout.LayoutParams(0, wrap(), 0.58f),
                )
                card.addView(rowLayout, linearMatch().withTopMargin(dp(10)))
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
            // 排查"检查更新失败 / 检查系统时间"类问题最关键的现场:
            // 设备当前时间(证书时间类失败的确定性依据)与最近一次错误文案。
            row("系统时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            row("最近错误", currentState.error ?: "无")
            row("可用内存", "${availMemMb}MB / ${totalMemMb}MB")

            val close = pill("关闭", selected = true, heightDp = 48, horizontalPaddingDp = 28) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(20)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
            close.post { close.requestFocus() }
        }.show()
    }

internal fun MainActivity.showAllPagesDialog(pages: PlaybackPages) {
        val activity = this
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                background = rounded(Colors.SURFACE_ELEVATED, 24, 1, Colors.GlassBorder)
            }
            card.addView(
                label("选集 · 共${pages.totalPages}集", 22f, Colors.TEXT_PRIMARY, bold = true, maxLines = 1),
                linearMatch(),
            )
            val scroll = ScrollView(activity)
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            var currentRow: View? = null
            for (i in 0 until pages.totalPages) {
                val title = pages.pageTitles.getOrElse(i) { "第${i + 1}集" }
                val isCurrent = i == pages.currentPage
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        dismiss()
                        viewModel.selectPage(i)
                    }
                    background = rounded(if (isCurrent) ColorUtils.setAlphaComponent(Colors.APPLE_BLUE, 38) else Color.TRANSPARENT, 12, 0, Color.TRANSPARENT)
                    val idxLabel = label("${i + 1}", 13f, Colors.TEXT_TERTIARY, bold = true, maxLines = 1).apply {
                        gravity = Gravity.CENTER
                        minWidth = dp(36)
                    }
                    addView(idxLabel, LinearLayout.LayoutParams(dp(36), wrap()))
                    val titleLabel = label(title, 15f, if (isCurrent) Colors.APPLE_BLUE else Colors.TEXT_PRIMARY, bold = isCurrent, maxLines = 1).apply {
                        layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).withStartMargin(dp(8))
                    }
                    addView(titleLabel)
                    // 焦点态:AppleBlue 背景 + 白色文字 + 微放大,与 pill 风格一致
                    setOnFocusChangeListener { _, focused ->
                        background = rounded(
                            when {
                                focused -> Colors.APPLE_BLUE
                                isCurrent -> ColorUtils.setAlphaComponent(Colors.APPLE_BLUE, 38)
                                else -> Color.TRANSPARENT
                            }, 12,
                            if (focused) 2 else 0,
                            if (focused) Colors.GLASS_BORDER_FOCUSED else Color.TRANSPARENT,
                        )
                        elevationCompat(if (focused) dp(6).toFloat() else 0f)
                        idxLabel.setTextColor(if (focused) Colors.TEXT_PRIMARY else Colors.TEXT_TERTIARY)
                        titleLabel.setTextColor(if (focused) Colors.TEXT_PRIMARY else if (isCurrent) Colors.APPLE_BLUE else Colors.TEXT_PRIMARY)
                        animate().cancel()
                        animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(140).start()
                    }
                }
                if (isCurrent) currentRow = row
                list.addView(row, linearMatch())
            }
            scroll.addView(list, frameMatch())
            card.addView(scroll, LinearLayout.LayoutParams(match(), 0, 1f).withTopMargin(dp(12)))
            val close = pill("关闭", selected = true, heightDp = 48, horizontalPaddingDp = 28) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(48)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.62f).toInt(), (resources.displayMetrics.heightPixels * 0.72f).toInt())
            val focusView = currentRow ?: close
            focusView.post { focusView.requestFocus() }
        }.show()
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
                label(notes, 16f, Colors.TEXT_SECONDARY, maxLines = 5).apply {
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
            card.addView(label(message, 17f, Colors.TEXT_SECONDARY, maxLines = 3), linearMatch())
            val close = pill("知道了", selected = true, heightDp = 44, horizontalPaddingDp = 24) { dismiss() }
            card.addView(close, LinearLayout.LayoutParams(wrap(), dp(44)).withTopMargin(dp(16)))
            setContentView(card)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout((resources.displayMetrics.widthPixels * 0.42f).toInt(), wrap())
            close.post { close.requestFocus() }
        }.show()
    }
