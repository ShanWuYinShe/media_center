package com.example.mediacenter.ui.search

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
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.example.mediacenter.ui.theme.tvButtonFocusScale
import com.example.mediacenter.ui.theme.tvPillButtonBorder
import com.example.mediacenter.ui.theme.tvPillButtonShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.providerDisplayName
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.home.VideoCard
import com.example.mediacenter.ui.player.SelectChip
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.CardGray
import com.example.mediacenter.ui.theme.CardSpacing
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.flow.distinctUntilChanged

// 搜索热词:TV 上用方向键输入很痛苦,空态展示常用词一键点选。
// 与 legacy 版保持一致,后续可随 provider 演进。
private val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)

// YouTube 的检索面比影视目录宽;复用电影类型热词会让用户以为搜不到别的内容。
private val YOUTUBE_HOT_KEYWORDS = listOf(
    "音乐", "纪录片", "科技", "旅行", "美食", "学习", "游戏", "电影",
)

@Composable
fun SearchScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val provider = MediaRepositoryHolder.repository.currentProvider

    BackHandler(onBack = onBack)

    // 断网自动恢复:网络恢复且上次搜索失败时自动重发(触发防抖搜索)。
    NetworkRestoreEffect(onRestored = {
        if (state.error != null && state.query.length >= 2) viewModel.search(state.query)
    })

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = ScreenHorizontalPadding, vertical = 24.dp)) {
        // 进入搜索页即聚焦输入框：遥控器第一下 OK 直接弹出 IME，不用先找输入框
        val searchFocus = remember { FocusRequester() }
        // 单行 TextField 会把方向键当作光标移动消费:显式把 DOWN 交给
        // 热词/首个结果,否则输入后遥控器永远出不了输入框。
        val firstHotFocus = remember { FocusRequester() }
        val firstResultFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.search(it) },
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeightIn(min = 56.dp)
                .focusRequester(searchFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
                        return@onPreviewKeyEvent false
                    }
                    val target = when {
                        state.query.length < 2 -> firstHotFocus
                        state.results.isNotEmpty() -> firstResultFocus
                        else -> null
                    }
                    if (target != null) {
                        target.requestFocus()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text("搜索视频 / 片名 / 关键词", color = TextTertiary) },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(state.query) }),
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.search("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索", tint = TextSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppleBlue,
                unfocusedBorderColor = DividerGray,
                cursorColor = AppleBlue,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )
        Spacer(Modifier.height(12.dp))
        // 剩余高度交给结果区:网格/空态/加载态都在同一块可视区里排布,
        // 避免 fillMaxSize 抢整屏约束导致内容被顶出屏幕。
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            state.isSearching -> LoadingIndicator()
            state.error != null -> ErrorView(
                message = state.error!!,
                modifier = Modifier.fillMaxSize(),
                onRetry = { viewModel.search(state.query) },
                autoFocusRetry = true,
            )
            state.query.length < 2 -> Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "输入至少 2 个字符搜索视频",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                // 热门搜索词:一键点选,免去方向键逐字输入。
                Text(
                    "热门搜索 · ${providerDisplayName(provider)}",
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                val hotKeywords = if (provider == "youtube") YOUTUBE_HOT_KEYWORDS else SEARCH_HOT_KEYWORDS
                hotKeywords.chunked(6).forEachIndexed { rowIndex, rowWords ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowWords.forEachIndexed { index, word ->
                            SelectChip(
                                label = word,
                                selected = false,
                                onClick = { viewModel.search(word) },
                                modifier = if (rowIndex == 0 && index == 0) {
                                    Modifier.focusRequester(firstHotFocus)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
            state.results.isEmpty() -> EmptyStateView(
                message = "没有找到相关视频",
                detail = "换个关键词或视频源再试试",
                actionLabel = "清空搜索",
                onAction = { viewModel.search("") },
            )
            else -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "搜索结果",
                        color = TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        buildString {
                            append("  ·  ")
                            if (state.total > state.results.size) {
                                append("已加载 ${state.results.size} / 共 ${state.total} 条")
                            } else {
                                append("${state.results.size} 条")
                            }
                            append(" · ${providerDisplayName(provider)}")
                        },
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 海报网格与首页视觉一致(纯文字行在 10ft 下识别度差、观感突兀);
                // GridCells.Adaptive 让卡片宽度自适应 720p/1080p/4K。
                val gridState = rememberLazyGridState()
                // 接近底部时拉下一页(历史实现只取第一页,结果被硬截断在 20 条)
                LaunchedEffect(gridState) {
                    snapshotFlow {
                        val layout = gridState.layoutInfo
                        (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
                    }
                        .distinctUntilChanged()
                        .collect { (lastVisible, count) ->
                            if (lastVisible >= 0 && count > 0 && lastVisible >= count - 4) {
                                viewModel.loadMore()
                            }
                        }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(240.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(CardSpacing),
                    verticalArrangement = Arrangement.spacedBy(CardSpacing),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    // 返回搜索结果时焦点回到原结果卡
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(),
                ) {
                    itemsIndexed(state.results, key = { _, video -> video.id }) { index, video ->
                        VideoCard(
                            video = video,
                            onClick = { onVideoClick(video) },
                            modifier = if (index == 0) {
                                Modifier
                                    .focusRequester(firstResultFocus)
                                    .focusProperties { up = searchFocus }
                            } else {
                                Modifier
                            },
                        )
                    }
                    if (state.isLoadingMore || state.loadMoreFailed) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.loadMoreFailed) {
                                    Button(
                                        shape = tvPillButtonShape(),
                                        border = tvPillButtonBorder(),
                                        scale = tvButtonFocusScale(),
                                        onClick = viewModel::retryLoadMore,
                                        modifier = Modifier.requiredWidth(200.dp).requiredHeight(48.dp),
                                        colors = ButtonDefaults.colors(
                                            containerColor = CardGray,
                                            focusedContainerColor = AppleBlue,
                                            contentColor = TextPrimary,
                                            focusedContentColor = TextPrimary,
                                        ),
                                    ) {
                                        Text("重试加载", color = TextPrimary)
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
        }
        }
    }
}
