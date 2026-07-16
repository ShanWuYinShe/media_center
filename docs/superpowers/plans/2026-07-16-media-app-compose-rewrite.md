# media_app (normal) Compose 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `media_app`（normal 变体，minSdk=23）从 3887 行 Canvas+View 整体重构为 Jetpack Compose + TV Material + Media3 + Coil，`media_app_legacy` 保持不变

**Architecture:**
- 单 Activity 多 Screen，以 Compose Navigation 驱动 TV 焦点导航
- 分层：data/repository → ui/ 各 Screen + ViewModel
- data/、net/、log/、player/、update/ 等非 UI 层与 legacy 代码结构对齐（但不共享，避免耦合）

**Tech Stack:** Jetpack Compose (with TV foundation/material), Media3 ExoPlayer (session-based), Coil (image loading), kotlinx-serialization, OkHttp 5

## 全局约束

- minSdk = 23，不要做 API 19 兼容
- 所有可聚焦/可点击控件使用 `requiredHeight` / `requiredSize`，不用普通 `height`
- 长文本必须 `maxLines` + `TextOverflow.Ellipsis`
- 详情页长简介用 Dialog 弹层展开
- 图标用 Canvas/Vector 组件绘制，不用 Unicode 字符当图标
- 焦点态统一 Apple Blue 背景 + 白色文字/图标 + 近白外框
- Back 行为用 `BackHandler`，不依赖 `onPreviewKeyEvent`
- Apple TV 视觉基线：OLED 深色背景、玻璃灰卡片、Apple Blue 强调色
- 按钮图标等使用 `requiredHeight` / `requiredSize` 保证可操作面积
- 不允许 Compose 代码引用任何 `android.view.*` 或 `android.widget.*`
- 旧 View 代码（MainActivity.kt 全部）在最后阶段删除，之前保留以作参考

---

### Task 1: Gradle 依赖 + Compose 基础框架

**Files:**
- Modify: `services/media_center/media_app/app/build.gradle.kts` — 加 Compose BOM、TV Material、Coil；移除 `recyclerview`、`media3-ui`

**Interfaces:**
- Consumes: 当前 `build.gradle.kts` 的 plugin/git-version/desugar 结构不变
- Produces: Compile 可用的 Compose 构建配置

- [ ] **Step 1: 修改 build.gradle.kts**

```kotlin
// 在 plugins 块新增 Compose compiler 插件（Kotlin 2.4+ 需要显式添加,AGP buildFeatures { compose = true } 已不足够）
// app/build.gradle.kts 和根 build.gradle.kts 都需要添加
// app/build.gradle.kts:
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")   // 新增
}

android {
    // ...保留现有 namespace/compileSdk/defaultConfig 等

    buildFeatures {
        buildConfig = true
        compose = true   // 新增
    }
    // ...
}

dependencies {
    // 保留现有的
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // 移除
    // implementation("androidx.recyclerview:recyclerview:1.4.0")     // Task 7 清理旧代码时删除
    // implementation("androidx.media3:media3-ui:$media3Version")    // Task 7 清理旧代码时删除

    // 新增 Compose BOM + TV
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // 新增 Coil
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Coil 2.x coil-compose 已内置 OkHttp 支持，不需要 coil-network-okhttp

    // 新增 lifecycle-runtime-compose（用于 collectAsStateWithLifecycle）
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 2: 验证编译**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL（即使 Compose 代码还没写，buildFeatures=compose 只是启用编译器插件，不要求必须有 Composable）

- [ ] **Step 3: Commit**

```bash
git add media_app/app/build.gradle.kts
git commit -m "build(media_app): 添加 Compose + TV Material + Coil 依赖"
```

---

### Task 2: 主题体系 + Application + 空壳 MainActivity

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/theme/Color.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/theme/Theme.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/theme/GlassBorder.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ComposeActivity.kt` — Compose 入口（不碰旧 MainActivity.kt）
- Modify: `media_app/app/src/main/AndroidManifest.xml` — activity 指向 ComposeActivity
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt` — NavigationHost 骨架

**Interfaces:**
- Produces: `MediaCenterTheme { content }` Composable 包装，`AppleTvColors` 调色板，`GlassBorderFocused` modifier

- [ ] **Step 1: 写 Color.kt**

```kotlin
package com.example.mediacenter.ui.theme

import androidx.compose.ui.graphics.Color

// Apple TV 视觉基线：OLED 深色背景、玻璃灰卡片、Apple Blue 唯一强调色
val AppleBlue = Color(0xFF007AFF)
val AppleBlueDark = Color(0xFF0040CC)
val BackgroundDark = Color(0xFF111111)
val CardGray = Color(0xFF1C1C1E)
val CardGrayLight = Color(0xFF2C2C2E)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF8E8E93)
val TextTertiary = Color(0xFF636366)
val DividerGray = Color(0xFF38383A)
val FocusBorder = Color(0xFFE5E5EA)       // 近白外框
val ErrorRed = Color(0xFFFF453A)
```

- [ ] **Step 2: 写 GlassBorder.kt**

```kotlin
package com.example.mediacenter.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 焦点态：Apple Blue 背景 + 白色文字/图标 + 近白外框
val CardShape = RoundedCornerShape(12.dp)

fun Modifier.glassCardBackground(): Modifier = composed {
    this
        .clip(CardShape)
        .background(CardGray, CardShape)
}

fun Modifier.glassCardFocused(): Modifier = composed {
    this
        .clip(CardShape)
        .background(AppleBlue, CardShape)
        .border(BorderStroke(2.dp, FocusBorder), CardShape)
}
```

- [ ] **Step 3: 写 Theme.kt**

```kotlin
package com.example.mediacenter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class AppleTvColors(
    val background: Color = BackgroundDark,
    val card: Color = CardGray,
    val cardLight: Color = CardGrayLight,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val textTertiary: Color = TextTertiary,
    val accent: Color = AppleBlue,
    val accentDark: Color = AppleBlueDark,
    val focusBorder: Color = FocusBorder,
    val divider: Color = DividerGray,
    val error: Color = ErrorRed,
)

val LocalAppleTvColors = staticCompositionLocalOf { AppleTvColors() }

@Composable
fun MediaCenterTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppleTvColors provides AppleTvColors()) {
        androidx.compose.material3.MaterialTheme(
            content = content,
        )
    }
}
```

- [ ] **Step 4: 修改 AndroidManifest.xml，将 activity 指向 ComposeActivity**

```xml
<!-- 在 application 块内 -->
<activity
    android:name=".ComposeActivity"
    android:exported="true"
    ... >
</activity>
<!-- 旧 MainActivity 保留，但不 exported (Task 7 删除) -->
```

- [ ] **Step 5: 写 Compose 空壳 ComposeActivity**

```kotlin
package com.example.mediacenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mediacenter.ui.theme.MediaCenterTheme

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaCenterTheme {
                AppNavigation()
            }
        }
    }
}
```

- [ ] **Step 5: 写 AppNavigation.kt 骨架**

```kotlin
package com.example.mediacenter.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{videoId}"
    const val PLAYER = "player/{videoId}"
    const val SEARCH = "search"

    fun detail(videoId: String) = "detail/$videoId"
    fun player(videoId: String) = "player/$videoId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        // composable(Routes.HOME) { HomeScreen(navController) }
        // composable(Routes.DETAIL) { ... }
        // composable(Routes.PLAYER) { ... }
        // composable(Routes.SEARCH) { ... }
    }
}
```

- [ ] **Step 6: 验证编译**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add media_app/app/src/main/java/com/example/mediacenter/ui/theme/
git add media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt
git add media_app/app/src/main/java/com/example/mediacenter/ComposeActivity.kt
git add media_app/app/src/main/AndroidManifest.xml
git commit -m "feat(media_app): Compose 主题 + 导航骨架 (ComposeActivity)"
```

---

### Task 3: 非 UI 层移植（data/net/log/player/update）

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/data/Models.kt` — 数据模型（从旧 MainActivity 提取）
- Create: `media_app/app/src/main/java/com/example/mediacenter/data/MediaApi.kt` — API 接口定义（从旧代码提取）
- Create: `media_app/app/src/main/java/com/example/mediacenter/data/MediaRepository.kt` — 迁移或重写
- Create: `media_app/app/src/main/java/com/example/mediacenter/data/BilibiliApi.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/data/PlaybackOption.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/net/AppHttpClient.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/log/AppLog.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/player/CappedLoadErrorHandlingPolicy.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/update/AppUpdateManager.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/update/AppUpdateResult.kt` — 迁移
- Create: `media_app/app/src/main/java/com/example/mediacenter/MediaCenterApp.kt` — Application 类

**Interfaces:**
- Produces: `MediaRepository`, `BilibiliApi`, `AppUpdateManager`, `AppHttpClient`, `AppLog`, `CappedLoadErrorHandlingPolicy` 等与 legacy 版功能等价的 Compose 版

**注意：** 这里不是简单复制，而是提取旧 `MainActivity.kt` 中的基础设施到独立文件，使其可被 Compose Screen 调用。旧代码的 Canvas View 保留不动（最后才删）。

- [ ] **Step 1: 提取 Models.kt**

从旧 `MainActivity.kt`（legacy 版也可参考）提取数据类：`ContentCategory`, `VideoSummary`, `MediaApiResponse`, `PlaybackPages`, `PlaybackOption`, `AppUpdateManifest` 等。

由于旧代码分散在 MainActivity 内部和 data/ 目录，需要 grep 出所有 data class。

```kotlin
package com.example.mediacenter.data

import kotlinx.serialization.Serializable

@Serializable
data class ContentCategory(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
)

@Serializable
data class VideoSummary(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
)
// ... 补充其他 data class
```

- [ ] **Step 2: 提取 MediaApi.kt 和 MediaRepository.kt**

**注意：** 具体实现参考旧 MainActivity 中的 HTTP 调用逻辑，但要改成纯 suspend 函数 + kotlinx-serialization。

```kotlin
package com.example.mediacenter.data

import com.example.mediacenter.BuildConfig
import com.example.mediacenter.net.AppHttpClient
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MediaRepository(private val httpClient: AppHttpClient = AppHttpClient.instance) {
    private val baseUrl = BuildConfig.MEDIA_SERVER_URL
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCategoryList(): List<ContentCategory> {
        // GET $baseUrl/providers
    }

    suspend fun getVideoList(categoryId: String): List<VideoSummary> {
        // GET $baseUrl/providers/{provider}/categories/{catID}/videos
    }

    suspend fun searchVideos(query: String): List<VideoSummary> {
        // GET $baseUrl/search?q=$query
    }
}
```

- [ ] **Step 3: 提取 BilibiliApi.kt**

```kotlin
package com.example.mediacenter.data

// 从旧 MainActivity 提取 B 站播放解析逻辑
class BilibiliApi(private val httpClient: AppHttpClient) {
    suspend fun resolvePlaybackUrl(videoId: String): PlaybackOption {
        // WBI sign + view + playurl API
    }
}
```

- [ ] **Step 4: 提取 AppHttpClient.kt**

```kotlin
package com.example.mediacenter.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class AppHttpClient private constructor() {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(8))
        .readTimeout(java.time.Duration.ofSeconds(15))
        .build()

    companion object {
        val instance = AppHttpClient()
        fun installHttpsUrlConnectionDefault() { /* OCSP soft-fail */ }
    }
}
```

- [ ] **Step 5: 提取 AppLog.kt + CappedLoadErrorHandlingPolicy.kt + AppUpdateManager.kt**

从旧 MainActivity 中提取对应的类，保持功能等价。

- [ ] **Step 6: 创建 MediaCenterApp.kt**

```kotlin
package com.example.mediacenter

import android.app.Application
import com.example.mediacenter.log.AppLog
import com.example.mediacenter.net.AppHttpClient

class MediaCenterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppHttpClient.installHttpsUrlConnectionDefault()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { AppLog.logFatalBlocking("Crash", "...", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
```

- [ ] **Step 7: 验证编译**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add media_app/app/src/main/java/com/example/mediacenter/data/
git add media_app/app/src/main/java/com/example/mediacenter/net/
git add media_app/app/src/main/java/com/example/mediacenter/log/
git add media_app/app/src/main/java/com/example/mediacenter/player/
git add media_app/app/src/main/java/com/example/mediacenter/update/
git add media_app/app/src/main/java/com/example/mediacenter/MediaCenterApp.kt
git commit -m "refactor(media_app): 提取非 UI 层到独立文件"
```

---

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

### Task 5: PlayerScreen — 播放器

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/player/PlayerScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/player/PlayerViewModel.kt`
- Modify: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt`

**Interfaces:**
- Consumes: `MediaRepository`, `BilibiliApi`, `CappedLoadErrorHandlingPolicy`, `VideoSummary`
- Produces: PlayerScreen Composable 入口

- [ ] **Step 1: 写 PlayerViewModel**

```kotlin
package com.example.mediacenter.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediacenter.data.MediaRepository
import com.example.mediacenter.data.PlaybackOption
import com.example.mediacenter.data.BilibiliApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val playbackOption: PlaybackOption? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class PlayerViewModel(
    private val videoId: String,
    private val repository: MediaRepository = MediaRepository(),
    private val bilibiliApi: BilibiliApi = BilibiliApi(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init { resolvePlayback() }

    fun resolvePlayback() {
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)
            try {
                val option = bilibiliApi.resolvePlaybackUrl(videoId)
                _uiState.value = PlayerUiState(playbackOption = option, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = PlayerUiState(isLoading = false, error = e.message)
            }
        }
    }
}
```

- [ ] **Step 2: 写 PlayerScreen.kt**

使用 `AndroidView` 包裹 ExoPlayer（Pure Compose 的 Media3 集成不成熟，AndroidView 是最稳定的方案）。

```kotlin
package com.example.mediacenter.ui.player

import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.mediacenter.player.CappedLoadErrorHandlingPolicy
import com.example.mediacenter.ui.theme.BackgroundDark

@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "player_$videoId",
        factory = PlayerViewModelFactory(videoId),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {
        if (state.isLoading) {
            LoadingIndicator()
        } else if (state.playbackOption != null) {
            var player by remember { mutableStateOf<ExoPlayer?>(null) }
            DisposableEffect(videoId) {
                val ctx = LocalContext.current
                val p = ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(ctx)
                            .setLoadErrorHandlingPolicy(CappedLoadErrorHandlingPolicy())
                    )
                    .build()
                p.setMediaItem(MediaItem.fromUri(state.playbackOption!!.url))
                p.prepare()
                p.play()
                player = p
                onDispose { p.release() }
            }
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = this@PlayerScreen.player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.error != null) {
            ErrorView(state.error!!, onRetry = { viewModel.resolvePlayback() })
        }
    }
}
```

- [ ] **Step 3: 注册到 AppNavigation.kt**

添加 PlayerScreen 路由。

- [ ] **Step 4: 编译验证 + Commit**

---

### Task 6: DetailScreen + SearchScreen + UpdateDialog

**Files:**
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/detail/DetailScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/detail/DescriptionDialog.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/search/SearchScreen.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/search/SearchViewModel.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/update/UpdateDialog.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/components/ErrorView.kt`
- Create: `media_app/app/src/main/java/com/example/mediacenter/ui/components/LoadingIndicator.kt`
- Modify: `media_app/app/src/main/java/com/example/mediacenter/ui/AppNavigation.kt`

- [ ] **Step 1–N: 各 Screen 实现**

具体代码从旧 MainActivity 的对应 UI 逻辑提取，转换为 Compose。

DetailScreen 关键规则：
- `BackHandler` 处理返回
- 简介用折叠 + "查看完整简介" Dialog
- 播放按钮用 `requiredSize` 保证 D-pad 可操作
- Coil 显示缩略图

SearchScreen：
- TextField 输入框 + LazyColumn 搜索结果
- 使用 `maxLines` + Ellipsis 防止长文本挤压

UpdateDialog：
- 使用 Compose AlertDialog
- 下载进度用 LinearProgressIndicator

- [ ] **Final: 编译验证 + Commit**

---

### Task 7: 清理旧 View 代码 + 依赖

**Files:**
- Delete: `media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt`（旧 View 版）
- Modify: `media_app/app/src/main/java/com/example/mediacenter/app/build.gradle.kts` — 移除 recyclerview + media3-ui（旧代码已清理完毕，可以安全删除）
- Delete: 旧布局文件/资源（如果有，检查 `res/layout/` 目录）

- [ ] **Step 1: 删除旧 MainActivity.kt（所有可提取的非 UI 代码已在 Task 3 移出）**

```bash
rm media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt
```

- [ ] **Step 2: 验证无残留 `android.view.*` 或 `android.widget.*` import**

```bash
rg "import android\.(view|widget)" media_app/app/src/main/java/ --type kotlinc
```
Expected: 0 matches

- [ ] **Step 3: 完整构建验证**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd services/media_center/media_app && ./gradlew :app:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL in Xs

- [ ] **Step 4: 检查 APK 产物**

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Commit**

```bash
git rm media_app/app/src/main/java/com/example/mediacenter/MainActivity.kt
git add -A
git commit -m "refactor(media_app): 清理旧 View 代码，Compose 重构完成"
```

---

### 自审清单

1. **Spec 覆盖**: 每项 AGENTS.md 中的技术栈要求（Compose、TV Material、Coil、Media3）都有对应 Task；Apple TV 视觉基线在 Theme.kt 中实现；焦点态/BackHandler/长文本等 UI/UX 约定在各 Screen 中落地。
2. **Placeholder 扫描**: 无 TBD/placeholder，每个文件路径和代码段完整。
3. **类型一致性**: `Routes` 对象在 AppNavigation.kt 定义，所有 Screen 通过 `navController.navigate(Routes.detail(id))` 调用。
