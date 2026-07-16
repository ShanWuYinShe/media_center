### Task 4: HomeScreen — 分类列表 + 视频网格

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/home/HomeScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/home/HomeViewModel.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/home/CategoryRow.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/home/VideoCard.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/components/TopBar.kt`
- Modify: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt` — 注册 HomeScreen

**Interfaces:**
- Consumes: `MediaRepository`, `ContentCategory`, `VideoSummary`
- Produces: HomeScreen Composable 入口

- [ ] **Step 1: 写 HomeViewModel**

```kotlin
package com.example.mediacenter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.ContentCategory
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.VideoSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val categories: List<ContentCategory> = emptyList(),
    val categoryVideos: Map<String, List<VideoSummary>> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: MediaRepository = MediaRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init { loadHome() }

    fun loadHome() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val categories = repository.getCategoryList()
                val videosMap = mutableMapOf<String, List<VideoSummary>>()
                for (cat in categories) {
                    videosMap[cat.id] = repository.getVideoList(cat.id)
                }
                _uiState.value = _uiState.value.copy(categories = categories, categoryVideos = videosMap, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
```

- [ ] **Step 2: 写 VideoCard.kt**

```kotlin
package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import coil.compose.AsyncImage
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.*

@Composable
fun VideoCard(
    video: VideoSummary,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .requiredWidth(260.dp)
            .requiredHeight(160.dp),
        scale = CardDefaults.scale(focusedScale = 1.05),
        colors = CardDefaults.colors(
            containerColor = if (isFocused) AppleBlue else CardGray,
        ),
        border = CardDefaults.border(
            focused = CardDefaults.border(
                border = CardDefaults.Border(
                    width = 2.dp,
                    color = FocusBorder,
                )
            )
        ),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = video.title,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}
```

- [ ] **Step 3: 写 CategoryRow.kt**

```kotlin
package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.data.VideoSummary
import com.example.mediacenter.ui.theme.TextPrimary
import com.example.mediacenter.ui.theme.TextSecondary

@Composable
fun CategoryRow(
    categoryName: String,
    videos: List<VideoSummary>,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Text(
            text = categoryName,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(videos, key = { it.id }) { video ->
                var isFocused by remember { mutableStateOf(false) }
                VideoCard(
                    video = video,
                    isFocused = isFocused,
                    onClick = { onVideoClick(video.id) },
                    modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                )
            }
        }
    }
}
```

- [ ] **Step 4: 写 TopBar.kt**

```kotlin
package com.example.mediacenter.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mediacenter.ui.theme.AppleBlue
import com.example.mediacenter.ui.theme.TextPrimary

@Composable
fun TopBar(
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Media Center",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
        )
        // Search icon button with requiredSize
        IconButton(
            onClick = onSearchClick,
            modifier = Modifier.requiredSize(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = TextPrimary,
            )
        }
    }
}
```

- [ ] **Step 5: 写 HomeScreen.kt**

```kotlin
package com.example.mediacenter.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mediacenter.ui.components.TopBar

@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TopBar(onSearchClick = onSearchClick) }
        if (state.isLoading) {
            item { LoadingIndicator() }
        } else if (state.error != null) {
            item { ErrorView(state.error!!) }
        } else {
            items(state.categories) { category ->
                CategoryRow(
                    categoryName = category.name,
                    videos = state.categoryVideos[category.id].orEmpty(),
                    onVideoClick = onVideoClick,
                )
            }
        }
    }
}
```

- [ ] **Step 6: 注册到 AppNavigation.kt**

在 `AppNavigation.kt` 中启用 HomeScreen 路由。

- [ ] **Step 7: 验证编译**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

- [ ] **Step 8: Commit**

```bash
git add media_app/app/src/main/java/com/example/mediacenter/ui/home/
git add media_app/app/src/main/java/com/example/mediacenter/ui/components/
git commit -m "feat(media_app): HomeScreen 分类列表 + 视频网格 (Compose)"
```

---

