package com.example.mediacenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mediacenter.ui.AppNavigation
import com.example.mediacenter.ui.theme.MediaCenterTheme

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaCenterTheme {
                AppNavigation()
            }
        }
        // 通知权限(POST_NOTIFICATIONS)不在启动时请求——TV 用户会困惑且可能拒绝;
        // 延迟到首次进入播放页时请求(见 PlayerScreen),媒体通知/系统媒体栏才需要它。
    }
}
