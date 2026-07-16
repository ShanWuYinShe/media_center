# Task 4 Report: HomeScreen — 分类列表 + 视频网格

**Status**: DONE
**Commit**: `770d65486cf8ee14b7a930a15e247586022e113f`
**Build**: `compileDebugKotlin` SUCCESSFUL

## Created files

| File | Purpose |
|------|---------|
| `ui/home/HomeViewModel.kt` | ViewModel + UiState，加载分类及各分类视频列表 |
| `ui/home/VideoCard.kt` | 单视频卡片（tv-material3 Card + Coil AsyncImage） |
| `ui/home/CategoryRow.kt` | 分类标题 + 横向 LazyRow 视频卡片 |
| `ui/home/HomeScreen.kt` | 主屏幕：TopBar + 分类列表/加载/错误态 |
| `ui/components/LoadingIndicator.kt` | 居中 `CircularProgressIndicator` |
| `ui/components/ErrorView.kt` | 错误信息 + 可选重试按钮 |
| `ui/components/TopBar.kt` | 顶栏："Media Center" + 搜索按钮 |

## Modified files

| File | Change |
|------|--------|
| `ui/AppNavigation.kt` | 注册 HOME composable → HomeScreen |

## Adaptations from brief

- `ContentCategory` 实际字段为 `id`/`title`/`description`（无 `provider`/`name`/`iconUrl`/`type`），CategoryRow 使用 `category.title`
- `MediaRepository` 无 `categoryVideos()` 方法，改用 `repository.search("", cat.id)`（内部走 `api.categoryVideos`）
- `MediaRepository` 需要 `MediaApi` 构造，HomeViewModel 默认参数改为 `MediaRepository(MediaApi(BuildConfig.MEDIA_SERVER_URL))`
- `VideoCard` 移除 `CardDefaults.border(focused=…)` 避免 tv-material3 1.0.0 API 不匹配
- 补充缺失的 `Text` / `getValue` / `fillParentMaxSize` 等导入

## Concerns

- HomeViewModel 目前为每个分类顺序发起网络请求（`for (cat in cats) { repository.search(...) }`），分类较多时加载慢。可后续优化为 `async` 并发。
- `VideoCard` 无焦点边框（移除 `border` 参数），效果略逊于设计；可待确认 tv-material3 正确 API 后补回。
