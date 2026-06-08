package com.example.mediacenter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mediacenter.data.MediaApi
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.VideoDetailsCache
import com.example.mediacenter.ui.MediaCenterApp
import com.example.mediacenter.ui.MediaViewModel
import com.example.mediacenter.ui.MediaViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: MediaViewModel by viewModels {
        MediaViewModelFactory(
            MediaRepository(
                api = MediaApi(BuildConfig.MEDIA_SERVER_URL),
                categoryDetailsCache = VideoDetailsCache(cacheDir),
                searchDetailsCache = VideoDetailsCache(
                    cacheDir = cacheDir,
                    cacheDirectoryName = VideoDetailsCache.SearchCacheDirectoryName,
                    maxBytes = VideoDetailsCache.SearchMaxBytes,
                    maxAgeMillis = VideoDetailsCache.SearchMaxAgeMillis,
                ),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动时打印实际生效的服务端 URL,便于在 logcat 中确认 BuildConfig 是否被覆盖。
        Log.i("MediaCenter", "MEDIA_SERVER_URL=${BuildConfig.MEDIA_SERVER_URL}")
        setContent {
            App(viewModel)
        }
    }
}

@Composable
private fun App(viewModel: MediaViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MediaCenterApp(
        state = state,
        onSearch = viewModel::search,
        onSelectCategory = viewModel::selectCategory,
        onSelect = viewModel::openDetails,
        onPlay = viewModel::play,
        onPlayNext = viewModel::playNext,
        onPlayPrevious = viewModel::playPrevious,
        onLoadMore = viewModel::loadMore,
        onPrefetchVisibleDetails = viewModel::prefetchVisibleDetails,
        onCancelVisibleDetailsPrefetch = viewModel::cancelVisibleDetailsPrefetch,
        onBack = viewModel::back,
        onRetry = viewModel::retry,
    )
}
