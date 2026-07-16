### Task 1: Gradle 依赖 + Compose 基础框架

**Files:**
- Modify: `services/media_center/media_app/app/build.gradle.kts` — 加 Compose BOM、TV Material、Coil；移除 `recyclerview`、`media3-ui`

**Interfaces:**
- Consumes: 当前 `build.gradle.kts` 的 plugin/git-version/desugar 结构不变
- Produces: Compile 可用的 Compose 构建配置

- [ ] **Step 1: 修改 build.gradle.kts**

```kotlin
// 在 plugins 块新增 Compose compiler 插件（Kotlin 2.4 内置）
// AGP 9+ 不需要 Compose compiler 插件，用 buildFeatures { compose = true } 即可

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
    // implementation("androidx.recyclerview:recyclerview:1.4.0")
    // implementation("androidx.media3:media3-ui:$media3Version")

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
    implementation("io.coil-kt:coil-network-okhttp:2.7.0")

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

