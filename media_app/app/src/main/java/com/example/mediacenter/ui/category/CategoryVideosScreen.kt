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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.home.VideoCard
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.TextPrimary
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.requiredSize(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "\u8FD4\u56DE", tint = TextPrimary)
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

        when {
            state.isLoadingInitial -> LoadingIndicator(modifier = Modifier.fillMaxSize())
            state.error != null -> ErrorView(state.error!!, onRetry = { viewModel.load(categoryId, title) })
            state.items.isEmpty() -> ErrorView("\u6682\u65E0\u5185\u5BB9")
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
        modifier = Modifier.fillMaxSize(),
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
                        OutlinedButton(onClick = onRetryLoadMore, modifier = Modifier.requiredWidth(200.dp)) {
                            Text("\u52A0\u8F7D\u66F4\u591A\u5931\u8D25\uFF0C\u70B9\u51FB\u91CD\u8BD5", color = TextPrimary)
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
