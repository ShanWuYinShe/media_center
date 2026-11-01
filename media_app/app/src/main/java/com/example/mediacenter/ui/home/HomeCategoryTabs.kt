package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.components.TvChip
import com.example.mediacenter.ui.theme.ScreenHorizontalPadding
import kotlinx.coroutines.delay

internal data class HomeTab(val id: String, val title: String)

/**
 * 顶部分类标签：焦点落上去即切换分类，不需要再按 OK 确认。
 *
 * 这是为遥控器盲操设计的：左右键在标签间移动，视线不必精确瞄准某个节点；
 * 停留超过 [FocusSelectDelayMs] 才真正发请求，快速划过不会把每个分类都加载一遍。
 */
@Composable
internal fun HomeCategoryTabs(
    tabs: List<HomeTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    selectedFocusRequester: FocusRequester,
    firstCardDownFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(tabs, selectedId) {
        val index = tabs.indexOfFirst { it.id == selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.focusRestorer(),
    ) {
        items(tabs, key = { it.id }) { tab ->
            var focused by remember(tab.id) { mutableStateOf(false) }
            LaunchedEffect(focused, tab.id) {
                if (focused && selectedId != tab.id) {
                    delay(FocusSelectDelayMs)
                    onSelect(tab.id)
                }
            }
            TvChip(
                label = tab.title,
                onClick = { onSelect(tab.id) },
                selected = tab.id == selectedId,
                maxWidth = 240.dp,
                modifier = Modifier
                    .then(
                        if (tab.id == selectedId) {
                            Modifier.focusRequester(selectedFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (firstCardDownFocusRequester != null) {
                            Modifier.focusProperties { down = firstCardDownFocusRequester }
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

private const val FocusSelectDelayMs = 120L
