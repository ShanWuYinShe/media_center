package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary

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
            // 错误文案可滚动，避免超长消息被截断（之前的 maxLines=5 会藏住关键信息）。
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text(
                text = message,
                color = ErrorRed,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                // 全屏错误层出现时遥控器焦点常常无处落；autoFocusRetry 把焦点
                // 明确放到“重试”上，用户直接按 OK 即可重试。
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (autoFocusRetry) retryFocus.requestFocus() }
                // tv Button + 项目统一焦点态(聚焦 Apple Blue 底/白字),替代 M3
                // OutlinedButton 的弱焦点指示,与 SelectChip/ActionRow 视觉一致。
                Button(
                    onClick = onRetry,
                    modifier = Modifier.requiredHeight(48.dp).focusRequester(retryFocus),
                    colors = ButtonDefaults.colors(
                        containerColor = CardGray,
                        focusedContainerColor = AppleBlue,
                        contentColor = TextPrimary,
                        focusedContentColor = TextPrimary,
                    ),
                ) {
                    Text("\u91CD\u8BD5", color = TextPrimary)
                }
            }
        }
    }
}
