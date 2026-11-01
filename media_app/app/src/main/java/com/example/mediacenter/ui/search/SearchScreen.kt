package com.example.mediacenter.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.MediaRepositoryHolder
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.data.providerDisplayName
import com.example.mediacenter.ui.components.EmptyStateView
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.PagedVideoGrid
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.components.TvIconButton
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.NetworkRestoreEffect
import kotlinx.coroutines.delay

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
        // 热词点击后结果区(加载中)承接焦点:否则热词 chip 被移出组合,焦点
        // 会落回输入框重新弹出键盘,把结果/加载态整个遮住。
        val resultsFocus = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        var focusResultsAfterHotWord by remember { mutableStateOf(false) }
        // 已点击热词、但该关键词尚未完成(防抖中或请求中)且没有结果:显示
        // 加载态并让结果区承接焦点,防止键盘抢回焦点。
        val hotWordPending = focusResultsAfterHotWord &&
            state.results.isEmpty() &&
            state.error == null &&
            state.completedQuery != state.query
        LaunchedEffect(hotWordPending, state.query, state.isSearching) {
            if (!hotWordPending) return@LaunchedEffect
            // 先让结果区拿到焦点,再等一帧隐藏 IME:输入框若在热词 chip
            // 移除的瞬间短暂拿回焦点,会立刻重新弹出键盘。
            runCatching { resultsFocus.requestFocus() }
            delay(120)
            keyboardController?.hide()
        }
        LaunchedEffect(state.isSearching, state.results, state.error, state.query, state.completedQuery) {
            if (!focusResultsAfterHotWord) return@LaunchedEffect
            when {
                state.results.isNotEmpty() -> {
                    focusResultsAfterHotWord = false
                    keyboardController?.hide()
                    runCatching { firstResultFocus.requestFocus() }
                }
                // 搜索已结束(成功但零结果/失败)或关键词被清空:释放 pending
                state.error != null || state.query.length < 2 ||
                    state.completedQuery == state.query -> {
                    focusResultsAfterHotWord = false
                }
            }
        }
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        OutlinedTextField(
            value = state.query,
            onValueChange = {
                // 用户手动输入时不再等热词结果抢焦点
                focusResultsAfterHotWord = false
                viewModel.search(it)
            },
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
                        // 离开输入框即收起软键盘,把屏幕让给热词/结果。
                        keyboardController?.hide()
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
                    TvIconButton(onClick = { viewModel.search("") }, contentDescription = "清除搜索") {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(resultsFocus)
                .then(if (hotWordPending) Modifier.focusable() else Modifier),
        ) {
        when {
            state.isSearching || hotWordPending -> LoadingIndicator()
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
                            TvChip(
                                label = word,
                                selected = false,
                                onClick = {
                                    keyboardController?.hide()
                                    focusResultsAfterHotWord = true
                                    viewModel.search(word)
                                },
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
                PagedVideoGrid(
                    items = state.results,
                    onItemClick = onVideoClick,
                    onLoadMore = viewModel::loadMore,
                    isLoadingMore = state.isLoadingMore,
                    loadMoreFailed = state.loadMoreFailed,
                    onRetryLoadMore = viewModel::retryLoadMore,
                    modifier = Modifier.weight(1f),
                    firstItemFocusRequester = firstResultFocus,
                    firstItemUpFocusRequester = searchFocus,
                )
            }
        }
        }
    }
}
