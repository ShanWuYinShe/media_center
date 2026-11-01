package com.example.mediacenter.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.components.PagedVideoGrid
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.ScreenVerticalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.util.NetworkRestoreEffect

@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onVideoClick: (VideoSummary) -> Unit,
    onBack: () -> Unit,
    viewModel: CategoryVideosViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // YouTube 分类是单页实时搜索,时长筛选需要上游过滤,当前不支持;隐藏筛选行。
    val isYouTubeProvider = MediaRepositoryHolder.repository.currentProvider == "youtube"

    BackHandler(onBack = onBack)

    // 断网自动恢复:网络可用且当前处于错误态时自动重载(与首页/播放页一致)。
    NetworkRestoreEffect(onRestored = {
        if (state.error != null) viewModel.load(categoryId, title)
    })

    // 导航到新分类时加载;同一 backStackEntry 重建不重复请求(load 内部有去重)
    LaunchedEffect(categoryId, title) { viewModel.load(categoryId, title) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = ScreenVerticalPadding),
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
        // YouTube 分类来自实时搜索,没有服务端时长过滤,不展示无效筛选。
        if (!isYouTubeProvider) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时长", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                DurationFilter.entries.forEach { filter ->
                    TvChip(
                        label = filter.label,
                        selected = filter == state.filter,
                        onClick = { viewModel.selectFilter(filter) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

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
                else -> PagedVideoGrid(
                    items = state.items,
                    onItemClick = onVideoClick,
                    onLoadMore = viewModel::loadMore,
                    isLoadingMore = state.isLoadingMore,
                    loadMoreFailed = state.loadMoreFailed,
                    onRetryLoadMore = viewModel::retryLoadMore,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
