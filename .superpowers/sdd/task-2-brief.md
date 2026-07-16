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

