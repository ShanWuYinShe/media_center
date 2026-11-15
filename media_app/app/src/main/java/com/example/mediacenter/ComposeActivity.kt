package com.example.mediacenter

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mediacenter.ui.AppNavigation
import com.example.mediacenter.ui.theme.MediaCenterTheme
import com.example.mediacenter.util.JankStatsReporter
import com.example.mediacenter.util.StartupTrace

class ComposeActivity : ComponentActivity() {
    private var jankStats: JankStatsReporter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 帧卡顿观测(仅日志、开销极低):阶段 0 现场基线与后续优化复核口径。
        jankStats = JankStatsReporter(window)
        setContent {
            MediaCenterTheme {
                AppNavigation()
            }
        }
        // 首帧绘制前记录 TTFF(进程→首帧):阶段 0 冷启动基线,只需一次。
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    StartupTrace.markFirstFrame("ComposeActivity")
                    return true
                }
            },
        )
        // 通知权限(POST_NOTIFICATIONS)不在启动时请求——TV 用户会困惑且可能拒绝;
        // 延迟到首次进入播放页时请求(见 PlayerNotificationPermission),媒体通知/系统媒体栏才需要它。
    }

    override fun onResume() {
        super.onResume()
        jankStats?.start()
    }

    override fun onPause() {
        super.onPause()
        jankStats?.stop()
    }
}
