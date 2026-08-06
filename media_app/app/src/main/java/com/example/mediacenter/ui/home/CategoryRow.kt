package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary

@Composable
fun CategoryRow(
    categoryName: String,
    videos: List<VideoSummary>,
    onVideoClick: (VideoSummary) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    loadFailed: Boolean = false,
) {
    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
            TextButton(onClick = onSeeAll) {
                Text(
                    text = "\u67E5\u770B\u5168\u90E8",
                    color = AppleBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (videos.isEmpty()) {
            // 加载中/无内容时给一行说明,而不是裸标题+空 LazyRow(用户会以为坏了)。
            // 加载失败与"真没有内容"区分展示,避免误导用户以为该分类确实为空。
            Text(
                text = if (loadFailed) "\u672C\u5206\u7C7B\u52A0\u8F7D\u5931\u8D25\uFF0C\u8BF7\u5237\u65B0\u91CD\u8BD5" else "\u672C\u5206\u7C7B\u6682\u65E0\u5185\u5BB9",
                color = if (loadFailed) AppleBlue else TextTertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(videos, key = { it.id }) { video ->
                    // LazyRow item 横/纵轴均无界:必须固定卡片宽度,aspectRatio 才能
                    // 稳定推导高度——否则尺寸回退到 AsyncImage 固有尺寸,占位图与
                    // 真实图片尺寸不同会引发加载前后布局跳变。
                    VideoCard(video = video, onClick = { onVideoClick(video) }, modifier = Modifier.width(240.dp))
                }
            }
        }
    }
}
