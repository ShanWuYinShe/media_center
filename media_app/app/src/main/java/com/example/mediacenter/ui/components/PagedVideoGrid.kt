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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
// 距列表末尾还剩 N 张卡时预取下一页(比"最后一行才请求"少一次可见等待)。
private const val LOAD_MORE_PREFETCH_DISTANCE = 8

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
    // 内容维度变化(分类/关键词)时滚动位置归零:缓存命中或结果替换时 Grid 不会重建,
    // rememberLazyGridState 会把上一个列表的滚动位置带过来,看起来像"分类内容错乱"。
    resetKey: Any? = null,
    // 从详情/播放/搜索返回时,把焦点恢复到离开前停留的卡片(而不是丢给顶栏)。
    // id 不存在(数据刷新/换分类)时不处理,交由调用方的默认焦点逻辑兜底。
    initialFocusItemId: String? = null,
    onItemFocused: ((String) -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var restoreFocusDone by remember { mutableStateOf(false) }
    // 只在"刚进入页面"的短窗口内恢复焦点:同一组合内切分类/翻页导致列表变化时
    // 不能把焦点从分类标签抢到网格里。
    val restoreWindowEndMs = remember { android.os.SystemClock.uptimeMillis() + 3000 }
    val restoreIndex = remember(items, initialFocusItemId) {
        if (initialFocusItemId == null) -1 else items.indexOfFirst { it.id == initialFocusItemId }
    }
    LaunchedEffect(restoreIndex, items.size, initialFocusItemId) {
        // 每次进入该页面(组合重建)只恢复一次;列表追加/刷新不会再次抢焦点。
        if (restoreFocusDone || restoreIndex < 0 || initialFocusItemId == null ||
            android.os.SystemClock.uptimeMillis() > restoreWindowEndMs
        ) {
            return@LaunchedEffect
        }
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == restoreIndex }
        if (!visible) gridState.scrollToItem(restoreIndex)
        // 目标项可能还没组合出来:连续等几帧再请求焦点。
        repeat(6) {
            withFrameNanos { }
            if (runCatching { restoreFocusRequester.requestFocus() }.isSuccess) {
                restoreFocusDone = true
                return@LaunchedEffect
            }
        }
    }

    // 只在 resetKey 真正变化(切分类/换关键词)时归零:首次组合或从详情/播放
    // 返回时,rememberSaveable 恢复的滚动位置必须保留,否则返回首页会被强制滚回
    // 顶部、焦点恢复也找不到目标(旧实现每次进入组合都执行 scrollToItem(0))。
    var lastResetKey by remember { mutableStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (lastResetKey == resetKey) return@LaunchedEffect
        lastResetKey = resetKey
        if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                // 提前两行左右(≈8 张卡)触发下一页:TV 网格一行 4-5 张,用户从
                // 倒数第二行开始按 → 时请求已在跑,滚到底不再先看一次 loading;
                // 短列表(canLoadMore=false)时这次调用是空操作。
                if (lastVisible >= 0 && total > 0 && lastVisible >= total - LOAD_MORE_PREFETCH_DISTANCE) {
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
        // 网格顶栏向上统一指向当前分类标签(或搜索框):第一排任意列卡片向上脱离
        // 网格时,事件冒泡到网格容器级声明的目标,避免 2D 空间搜索落入正上方的
        // 其他分类标签导致意外自动切类。第二排及后续卡片仍正常在网格内向上翻行。
        // 当底部加载更多失败时,最后一排任意列卡片向下离开网格统一跳至重试按钮。
        modifier = modifier
            .fillMaxWidth()
            .focusRestorer()
            .then(
                if (firstItemUpFocusRequester != null) {
                    Modifier.focusProperties { up = firstItemUpFocusRequester }
                } else {
                    Modifier
                },
            )
            .then(
                if (loadMoreFailed) {
                    Modifier.focusProperties { down = retryFocus }
                } else {
                    Modifier
                },
            ),
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
            val restoreModifier = if (video.id == initialFocusItemId) {
                Modifier.focusRequester(restoreFocusRequester)
            } else {
                Modifier
            }
            val focusedModifier = onItemFocused?.let { callback ->
                Modifier.onFocusChanged { if (it.isFocused) callback(video.id) }
            } ?: Modifier
            VideoCard(
                video = video,
                onClick = { onItemClick(video) },
                modifier = Modifier.fillMaxWidth().then(firstModifier).then(restoreModifier).then(focusedModifier),
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
                            modifier = Modifier
                                .requiredWidth(200.dp)
                                .focusRequester(retryFocus),
                        )
                    } else {
                        CircularProgressIndicator(color = AppleBlue, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
