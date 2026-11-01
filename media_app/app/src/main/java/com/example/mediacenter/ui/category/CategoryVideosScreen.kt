package com.example.mediacenter.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.home.VideoCard
import com.example.mediacenter.ui.player.SelectChip
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.CardSpacing
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onVideoClick: (VideoSummary) -> Unit,
    onBack: () -> Unit,
    viewModel: CategoryVideosViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // 断网自动恢复:网络可用且当前处于错误态时自动重载(与首页/播放页一致)。
    NetworkRestoreEffect(onRestored = {
        if (state.error != null) viewModel.load(categoryId, title)
    })

    // 导航到新分类时加载;同一 backStackEntry 重建不重复请求(load 内部有去重)
    LaunchedEffect(categoryId, title) { viewModel.load(categoryId, title) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(onClick = onBack, contentDescription = "\u8FD4\u56DE") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = state.title.ifEmpty { title },
                    color = TextPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 总数来自服务端当前档位的 total(不是已加载条数),一眼可见内容规模
                if (state.total > 0) {
                    Text(
                        text = "共 ${state.total} 部",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // 时长筛选行:全部 / 短片 / 中片 / 长片。切换即按新档位向服务端重新拉取。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("时长", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            DurationFilter.entries.forEach { filter ->
                SelectChip(
                    label = filter.label,
                    selected = filter == state.filter,
                    onClick = { viewModel.selectFilter(filter) },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 用 weight 占满剩余高度,让加载/错误/空态在可视区真正居中;
        // 直接 fillMaxSize 会拿到整屏约束,和上方标题/筛选重叠。
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoadingInitial -> LoadingIndicator(modifier = Modifier.fillMaxSize())
                state.error != null -> ErrorView(
                    message = state.error!!,
                    onRetry = { viewModel.load(categoryId, title) },
                    autoFocusRetry = true,
                )
                state.items.isEmpty() -> EmptyStateView(
                    message = if (state.filter == DurationFilter.ALL) "暂无内容" else "当前时长档位暂无内容",
                    detail = if (state.filter == DurationFilter.ALL) {
                        "该分类还没有已上线的视频"
                    } else {
                        "换个时长档位看看"
                    },
                    actionLabel = if (state.filter == DurationFilter.ALL) null else "查看全部",
                    onAction = if (state.filter == DurationFilter.ALL) null else {
                        { viewModel.selectFilter(DurationFilter.ALL) }
                    },
                    autoFocusAction = state.filter != DurationFilter.ALL,
                )
                else -> CategoryGrid(
                    items = state.items,
                    isLoadingMore = state.isLoadingMore,
                    loadMoreFailed = state.loadMoreFailed,
                    onVideoClick = onVideoClick,
                    onLoadMore = viewModel::loadMore,
                    onRetryLoadMore = viewModel::retryLoadMore,
                )
            }
        }
    }
}

// 网格 + 滚动到底自动翻页 + 底部 loading/重试 footer。
@Composable
private fun CategoryGrid(
    items: List<VideoSummary>,
    isLoadingMore: Boolean,
    loadMoreFailed: Boolean,
    onVideoClick: (VideoSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    val gridState: LazyGridState = rememberLazyGridState()

    // 接近底部(剩余不足 4 个)时触发加载下一页。同时观察 totalItemsCount:
    // 只观察 lastVisible 时,新一页若恰好填满可视区(索引不变),会一直不触发
    // 下一页,列表在"还有更多"时卡住。
    LaunchedEffect(gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (lastVisible >= 0 && total > 0 && lastVisible >= total - 4) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(240.dp),
        state = gridState,
        contentPadding = PaddingValues(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(CardSpacing),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
        // 返回时焦点回到离开前的卡片（配合滚动位置保存）
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(items, key = { it.id }) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video) }, modifier = Modifier.fillMaxWidth())
        }
        if (isLoadingMore || loadMoreFailed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loadMoreFailed) {
                        // 统一 TV 焦点态(Apple Blue 底/白字),与 ErrorView 重试一致
                        Button(
                            shape = tvPillButtonShape(),
                            border = tvPillButtonBorder(),
                            scale = tvButtonFocusScale(),
                            onClick = onRetryLoadMore,
                            modifier = Modifier.requiredWidth(200.dp).requiredHeight(48.dp),
                            colors = ButtonDefaults.colors(
                                containerColor = CardGray,
                                focusedContainerColor = AppleBlue,
                                contentColor = TextPrimary,
                                focusedContentColor = TextPrimary,
                            ),
                        ) {
                            Text(
                                "\u91CD\u8BD5\u52A0\u8F7D",
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            color = AppleBlue,
                            modifier = Modifier.requiredSize(32.dp),
                        )
                    }
                }
            }
        }
    }
}
