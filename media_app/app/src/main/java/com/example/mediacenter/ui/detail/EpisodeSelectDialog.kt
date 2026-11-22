package com.example.mediacenter.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.components.TvDialogButton
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.AppleBlueDark
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.GlassFill
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape

/**
 * 详情页"查看全部"选集弹窗。
 *
 * 分P可能上百集,用 LazyColumn 惰性建行(与 legacy 的 RecyclerView 选集弹窗同口径);
 * 打开时滚动并聚焦当前集,遥控器上下键直接翻列表选集。
 */
@Composable
internal fun EpisodeSelectDialog(
    pageTitles: List<String>,
    totalPages: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (totalPages <= 0) return
    val currentFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(totalPages, currentPage) {
        val target = currentPage.coerceIn(0, totalPages - 1)
        // 目标行可能还没组合(长列表):先滚到目标附近,再等几帧重试聚焦;
        // 失败时退到"关闭",保证弹窗内始终有可操作的焦点。
        runCatching { listState.scrollToItem(maxOf(0, target - 2)) }
        repeat(8) {
            withFrameNanos { }
            if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        runCatching { closeFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选集 · 共 $totalPages 集",
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(count = totalPages, key = { it }) { index ->
                    val isCurrent = index == currentPage.coerceIn(0, totalPages - 1)
                    val title = pageTitles.getOrNull(index).orEmpty()
                    Button(
                        onClick = { onSelect(index) },
                        shape = tvPillButtonShape(),
                        border = tvPillButtonBorder(),
                        scale = tvButtonFocusScale(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(48.dp)
                            .then(if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isCurrent) AppleBlueDark else GlassFill,
                            focusedContainerColor = AppleBlue,
                            contentColor = TextPrimary,
                            focusedContentColor = TextPrimary,
                        ),
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = TextTertiary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.width(40.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = title.ifBlank { "第 ${index + 1} 集" },
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TvDialogButton(
                label = "关闭",
                onClick = onDismiss,
                primary = true,
                modifier = Modifier.focusRequester(closeFocus),
            )
        },
        containerColor = CardGray,
    )
}
