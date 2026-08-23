package com.example.mediacenter.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.home.VideoCard
import com.example.mediacenter.ui.player.SelectChip
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 时长筛选档位(分类"查看全部"列表用):数据来自 VideoSummary.durationSeconds
 * (服务端 catalog 已有字段,纯客户端过滤,无需额外请求)。
 * 阈值:短片 < 10 分钟;中片 10~45 分钟;长片 > 45 分钟。
 */
enum class DurationFilter(val id: String, val label: String) {
    ALL("all", "全部"),
    SHORT("short", "短片"),
    MEDIUM("medium", "中片"),
    LONG("long", "长片"),
}

/** 视频是否命中某档时长筛选;时长缺失(null)的视频只出现在"全部"里。 */
private fun VideoSummary.matchesDuration(filter: DurationFilter): Boolean {
    val seconds = durationSeconds ?: return false
    return when (filter) {
        DurationFilter.ALL -> true
        DurationFilter.SHORT -> seconds < 10 * 60
        DurationFilter.MEDIUM -> seconds in 10 * 60 until 45 * 60
        DurationFilter.LONG -> seconds >= 45 * 60
    }
}

@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onVideoClick: (VideoSummary) -> Unit,
    onBack: () -> Unit,
    viewModel: CategoryVideosViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 时长筛选(本页内状态):过滤已加载列表,不触发重新请求;切回"全部"即恢复。
    var durationFilter by remember { mutableStateOf(DurationFilter.ALL) }
    val displayItems = remember(state.items, durationFilter) {
        if (durationFilter == DurationFilter.ALL) state.items
        else state.items.filter { it.matchesDuration(durationFilter) }
    }

    BackHandler(onBack = onBack)

    // 断网自动恢复:网络可用且当前处于错误态时自动重载(与首页/播放页一致)。
    NetworkRestoreEffect(onRestored = {
        if (state.error != null) viewModel.load(categoryId, title)
    })

    // 导航到新分类时加载;同一 backStackEntry 重建不重复请求(load 内部有去重)
    LaunchedEffect(categoryId, title) { viewModel.load(categoryId, title) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(onClick = onBack, contentDescription = "\u8FD4\u56DE") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
            }
            Text(
                text = state.title.ifEmpty { title },
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // 时长筛选行:全部 / 短片 / 中片 / 长片。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DurationFilter.entries.forEach { filter ->
                SelectChip(
                    label = filter.label,
                    selected = filter == durationFilter,
                    onClick = { durationFilter = filter },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoadingInitial -> LoadingIndicator(modifier = Modifier.fillMaxSize())
            state.error != null -> ErrorView(message = state.error!!, onRetry = { viewModel.load(categoryId, title) })
            displayItems.isEmpty() -> ErrorView(
                message = if (state.items.isEmpty()) "\u6682\u65E0\u5185\u5BB9" else "当前筛选下暂无内容",
            )
            else -> CategoryGrid(
                items = displayItems,
                isLoadingMore = state.isLoadingMore,
                loadMoreFailed = state.loadMoreFailed,
                onVideoClick = onVideoClick,
                onLoadMore = viewModel::loadMore,
                onRetryLoadMore = viewModel::retryLoadMore,
            )
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

    // 接近底部(剩余不足 2 行)时触发加载下一页
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = gridState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 4) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(280.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
