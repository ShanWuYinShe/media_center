package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.ErrorRed

@Composable
fun ErrorView(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null,
    autoFocusRetry: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 错误文案用遥控器可滚动文本:普通 verticalScroll 对 D-pad 无效,
            // TV 上超出一屏的长错误信息等于不可读(方向键只会移到重试按钮)。
            modifier = Modifier.padding(24.dp),
        ) {
            TvScrollableText(
                text = message,
                color = ErrorRed,
                style = MaterialTheme.typography.bodyMedium,
                maxHeight = 280.dp,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                // 全屏错误层出现时遥控器焦点常常无处落；autoFocusRetry 把焦点
                // 明确放到“重试”上，用户直接按 OK 即可重试。
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (autoFocusRetry) retryFocus.requestFocus() }
                // tv Button + 项目统一焦点态(聚焦 Apple Blue 底/白字),替代 M3
                // OutlinedButton 的弱焦点指示,与 SelectChip/ActionRow 视觉一致。
                TvActionButton(
                    label = "重试",
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                )
            }
        }
    }
}
