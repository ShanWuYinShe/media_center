package com.example.mediacenter.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.DividerGray
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextTertiary
import com.example.mediacenter.util.NetworkRestoreEffect

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
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.search(it) },
            modifier = Modifier.fillMaxWidth().requiredHeight(56.dp),
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
            }
            state.results.isEmpty() -> {
                Text(
                    "\u672A\u627E\u5230\u76F8\u5173\u89C6\u9891",
                    color = TextTertiary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.results, key = { it.id }) { video ->
                        androidx.tv.material3.Card(
                            onClick = { onVideoClick(video) },
                            modifier = Modifier.fillMaxWidth().requiredHeight(48.dp),
                        ) {
                            Text(
                                video.title,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
