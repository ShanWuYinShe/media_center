package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardSpacing
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.VideoGridMinWidth
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 目录/搜索共用的海报网格:
 * - 接近底部自动加载下一页;
 * - 底部 loading / 重试 footer;
 * - 焦点恢复 + 可选首卡焦点/上焦点(搜索页从输入框下移)。
 *
 * 分页触发同时观察 lastVisible 与 totalItemsCount:只观察 lastVisible 时,
 * 新一页恰好填满可视区(索引不变)会卡住不再加载。
 */
@Composable
fun PagedVideoGrid(
    items: List<VideoSummary>,
    onItemClick: (VideoSummary) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    onRetryLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    firstItemUpFocusRequester: FocusRequester? = null,
    progressById: Map<String, Float> = emptyMap(),
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (lastVisible >= 0 && total > 0 && lastVisible >= total - 4) {
                    onLoadMore()
                }
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(VideoGridMinWidth),
        state = gridState,
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            bottom = 32.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(CardSpacing),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
        modifier = modifier.fillMaxWidth().focusRestorer(),
    ) {
        itemsIndexed(items, key = { _, video -> video.id }) { index, video ->
            val firstModifier = if (index == 0) {
                Modifier
                    .then(
                        if (firstItemFocusRequester != null) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (firstItemUpFocusRequester != null) {
                            Modifier.focusProperties { up = firstItemUpFocusRequester }
                        } else {
                            Modifier
                        },
                    )
            } else {
                Modifier
            }
            VideoCard(
                video = video,
                onClick = { onItemClick(video) },
                modifier = Modifier.fillMaxWidth().then(firstModifier),
                progress = progressById[video.id],
            )
        }
        if (isLoadingMore || loadMoreFailed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loadMoreFailed) {
                        TvActionButton(
                            label = "重试加载",
                            onClick = onRetryLoadMore,
                            modifier = Modifier.requiredWidth(200.dp),
                        )
                    } else {
                        CircularProgressIndicator(color = AppleBlue, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
