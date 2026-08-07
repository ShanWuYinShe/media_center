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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.ErrorRed
import com.example.mediacenter.ui.theme.TextPrimary

@Composable
fun ErrorView(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null,
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
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.requiredHeight(48.dp),
                ) {
                    Text("\u91CD\u8BD5", color = TextPrimary)
                }
            }
        }
    }
}
