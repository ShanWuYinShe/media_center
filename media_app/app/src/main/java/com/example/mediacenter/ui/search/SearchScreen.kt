package com.example.mediacenter.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.home.VideoCard
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.player.SelectChip
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.NetworkRestoreEffect

// 搜索热词:TV 上用方向键输入很痛苦,空态展示常用词一键点选。
// 与 legacy 版保持一致,后续可随 provider 演进。
private val SEARCH_HOT_KEYWORDS = listOf(
    "动作", "喜剧", "科幻", "恐怖", "爱情", "悬疑",
    "国产", "港片", "好莱坞", "纪录", "战争", "动画",
)

@Composable
fun SearchScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // 断网自动恢复:网络恢复且上次搜索失败时自动重发(触发防抖搜索)。
    NetworkRestoreEffect(onRestored = {
        if (state.error != null && state.query.length >= 2) viewModel.search(state.query)
    })

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 进入搜索页即聚焦输入框：遥控器第一下 OK 直接弹出 IME，不用先找输入框
        val searchFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.search(it) },
            modifier = Modifier.fillMaxWidth().requiredHeight(56.dp).focusRequester(searchFocus),
            placeholder = { Text("搜索视频...", color = TextTertiary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppleBlue,
                unfocusedBorderColor = DividerGray,
                cursorColor = AppleBlue,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.isSearching -> LoadingIndicator()
            state.error != null -> ErrorView(message = state.error!!, modifier = Modifier.fillMaxSize())
            state.query.length < 2 -> {
                Text(
                    "\u8F93\u5165\u81F3\u5C11 2 \u4E2A\u5B57\u7B26\u641C\u7D22\u89C6\u9891",
                    color = TextTertiary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                // 热门搜索词:一键点选,免去方向键逐字输入。
                Text("\u70ED\u95E8\u641C\u7D22", color = TextTertiary, modifier = Modifier.padding(bottom = 12.dp))
                SEARCH_HOT_KEYWORDS.chunked(6).forEach { rowWords ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowWords.forEach { word ->
                            SelectChip(label = word, selected = false, onClick = { viewModel.search(word) })
                        }
                    }
                }
            }
            state.results.isEmpty() -> {
                Text(
                    "\u672A\u627E\u5230\u76F8\u5173\u89C6\u9891",
                    color = TextTertiary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                // 海报网格与首页视觉一致(纯文字行在 10ft 下识别度差、观感突兀);
                // GridCells.Adaptive 让卡片宽度自适应 720p/1080p/4K。
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    // 返回搜索结果时焦点回到原结果卡
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                ) {
                    items(state.results, key = { it.id }) { video ->
                        VideoCard(video = video, onClick = { onVideoClick(video) })
                    }
                }
            }
        }
    }
}
