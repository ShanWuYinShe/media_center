package com.example.mediacenter.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * 断网自动恢复:注册默认网络回调,网络从断开恢复到可用时调用 [onRestored]。
 * 首页/播放页用它做"恢复后自动重试"——当前处于错误态时重试一次,
 * 正常态时忽略,避免打断正在进行的操作。
 */
@Composable
fun NetworkRestoreEffect(onRestored: () -> Unit) {
    val context = LocalContext.current
    val currentOnRestored by rememberUpdatedState(onRestored)
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { currentOnRestored() }
            }
        }
        // registerDefaultNetworkCallback 需要 API 24;API 23 设备降级为无自动恢复
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { cm.registerDefaultNetworkCallback(callback) }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }
}
