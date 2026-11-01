package com.example.mediacenter.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Android 13+ 通知权限:首次进入播放页时请求。
 *
 * 系统媒体栏/媒体通知需要该权限;不在 App 启动时弹窗打扰用户,拒绝也不影响
 * 播放。抽出独立 composable,避免和音频焦点/控制条逻辑混在 PlayerContent 里。
 */
@Composable
internal fun PlayerNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 拒绝只影响媒体通知,不打断播放;记录已询问,避免每次进播放页都弹窗。
        prefs.edit { putBoolean(NOTIFICATION_PERMISSION_ASKED, true) }
    }
    LaunchedEffect(Unit) {
        if (context is Activity &&
            !prefs.getBoolean(NOTIFICATION_PERMISSION_ASKED, false) &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 先落标记再弹系统框:系统框被 Activity 重建打断时也不会反复打扰。
            prefs.edit { putBoolean(NOTIFICATION_PERMISSION_ASKED, true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private const val NOTIFICATION_PREFS = "media_prefs"
private const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
