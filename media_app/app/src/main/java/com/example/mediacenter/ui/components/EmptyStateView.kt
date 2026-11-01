package com.example.mediacenter.ui.components
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary

/**
 * 中性的空状态(不是错误):用于"暂无内容 / 当前筛选为空"等场景。
 *
 * 与 [ErrorView] 分开的原因:空内容是正常结果,用红色错误文案会让用户
 * 误以为播放/网络坏了;这里用灰阶 + 弱化的播放图标表达"这里暂时没有东西"。
 * 可选动作按钮(如"查看全部")在需要时提供明确的下一步。
 */
@Composable
fun EmptyStateView(
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    autoFocusAction: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(CardGray, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = TextSecondary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                val actionFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (autoFocusAction) actionFocus.requestFocus() }
                TvActionButton(
                    label = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.focusRequester(actionFocus),
                )
            }
        }
    }
}
