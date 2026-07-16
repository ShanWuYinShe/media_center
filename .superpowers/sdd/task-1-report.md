# Task 1 Report: Gradle 依赖 + Compose 基础框架

## 变更内容

**修改文件:**
- `media_app/app/build.gradle.kts` — 主要变更
- `media_app/build.gradle.kts` — 添加 Compose compiler 插件声明

**具体变更:**
1. **新增 `kotlin.plugin.compose` 插件**: Kotlin 2.4+ 需要显式添加 Compose compiler Gradle plugin（`buildFeatures { compose = true }` 已不足够）。在 `app/` 和根 `build.gradle.kts` 均添加。
2. **`buildFeatures { compose = true }`**: 在 `android` 块内启用 Compose。
3. **新增 Compose/TV 依赖**: Compose BOM 2024.12.01、UI、Foundation、Material3、TV Material/Foundation、Activity Compose、Navigation Compose、Coil、lifecycle-runtime-compose。
4. **保留的依赖**: `recyclerview` 和 `media3-ui` — 计划中要移除，但现有代码（`MainActivity.kt`）仍直接使用 RecyclerView 和 `PlayerView`；移除会导致编译失败。已加 `// TODO` 标记，待后续 UI 迁移后清理。

## 构建结果

```
BUILD SUCCESSFUL in 11s
```

仅有一个 pre-existing warning（`MediaViewModel.kt:639` Elvis 操作符左值非 null 的 lint）。

## Commit

```
c28cb9c build(media_app): 添加 Compose + TV Material + Coil 依赖
```

## 关注点

1. `recyclerview:1.4.0` 和 `media3-ui` 虽计划移除，但代码仍有直接引用，暂保留；后续任务需同步移除。
2. Gradle 9.6 + AGP 9.2 有 configuration cache 序列化 bug（`DefaultConfigurableFileCollection`），需用 `--no-configuration-cache` 绕过。
3. `coil-network-okhttp` 在 Coil 2.x 中不存在（是 3.x artifact），Coil 2.7 的 `coil-compose` 已内置 OkHttp 支持。
