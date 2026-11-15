package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.TextSecondary

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    // 可选说明文案:首屏解析/换源这类"等待较长"的场景给用户一句明确状态,
    // 比纯转圈更能表达"在干嘛、还要等多久"。
    message: String? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AppleBlue)
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
