package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.components.VideoCard
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardSpacing
import com.example.mediacenter.ui.theme.RowVerticalSpacing
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary

@Composable
fun CategoryRow(
    categoryName: String,
    videos: List<VideoSummary>,
    onVideoClick: (VideoSummary) -> Unit,
    modifier: Modifier = Modifier,
    // 可选"查看全部":继续观看行没有对应的分类页,传 null 隐藏入口
    onSeeAll: (() -> Unit)? = null,
    // 按 videoId 的观看进度(0f..1f),继续观看行用
    progressById: Map<String, Float> = emptyMap(),
    loadFailed: Boolean = false,
    // 分类首屏加载失败时的重试动作;null 时仅展示失败文案
    onRetry: (() -> Unit)? = null,
) {
    // "查看全部"在行标题右侧,空间焦点搜索从卡片上移时会落到上一行卡片,
    // 几乎无法选中它。给首张卡片显式指定 up 目标,保证遥控器可达。
    val seeAllFocus = remember { FocusRequester() }

    Column(modifier = modifier.padding(vertical = RowVerticalSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = categoryName,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            onSeeAll?.let { onClick ->
                // 用胶囊按钮而非 TextButton:TV 上 Material TextButton 的焦点
                // 指示很弱,而"查看全部"是要用遥控器精确选中的动作。
                TvChip(
                    label = "查看全部",
                    onClick = onClick,
                    modifier = Modifier.focusRequester(seeAllFocus),
                )
            }
        }
        if (videos.isEmpty()) {
            // 加载中/无内容时给一行说明,而不是裸标题+空 LazyRow(用户会以为坏了)。
            // 加载失败与"真没有内容"区分展示,避免误导用户以为该分类确实为空。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (loadFailed) "本分类加载失败" else "本分类暂无内容",
                    color = if (loadFailed) AppleBlue else TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                // TV 没有下拉刷新:失败行必须给一个遥控器可达的重试入口。
                if (loadFailed && onRetry != null) {
                    TvChip(label = "重试", onClick = onRetry)
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(CardSpacing),
                // 焦点恢复：从详情/播放页返回时焦点落回离开前的那张卡片，
                // 而不是行首（TV 上丢失浏览位置是高频烦恼）。
                modifier = Modifier.focusRestorer(),
            ) {
                itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                    // LazyRow item 横/纵轴均无界:必须固定卡片宽度,aspectRatio 才能
                    // 稳定推导高度——否则尺寸回退到 AsyncImage 固有尺寸,占位图与
                    // 真实图片尺寸不同会引发加载前后布局跳变。
                    val focusModifier = if (index == 0 && onSeeAll != null) {
                        Modifier.focusProperties { up = seeAllFocus }
                    } else {
                        Modifier
                    }
                    VideoCard(
                        video = video,
                        onClick = { onVideoClick(video) },
                        progress = progressById[video.id],
                        modifier = Modifier.width(240.dp).then(focusModifier),
                    )
                }
            }
        }
    }
}
