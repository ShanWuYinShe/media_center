package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.components.ErrorView
import com.example.mediacenter.ui.components.LoadingIndicator
import com.example.mediacenter.ui.components.TopBar

@Composable
fun HomeScreen(
    onVideoClick: (VideoSummary) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TopBar(onSearchClick = onSearchClick) }
        when {
            state.isLoading -> item { LoadingIndicator() }
            state.error != null -> item { ErrorView(state.error!!, onRetry = { viewModel.loadHome() }) }
            state.categories.isEmpty() -> item {
                ErrorView("\u6682\u65E0\u5206\u7C7B\u6570\u636E")
            }
            else -> items(state.categories) { category ->
                CategoryRow(
                    categoryName = category.title,
                    videos = state.categoryVideos[category.id].orEmpty(),
                    onVideoClick = onVideoClick,
                )
            }
        }
    }
}
