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

