# Task 3 Report: 非 UI 层移植

## 状态: DONE

所有非 UI 层文件已存在于 `media_app/app/src/main/java/com/example/mediacenter/`，编译 `BUILD SUCCESSFUL`。

## 文件清单

| 文件 | 状态 | 备注 |
|------|------|------|
| `data/Models.kt` | ✅ 已存在 | 含 ProviderInfo, ContentCategory, VideoSummary, PlaybackOption, PlaybackPages, AppUpdateInfo 等 |
| `data/MediaApi.kt` | ✅ 已存在 | OkHttp + kotlinx-serialization, 网络错误分类, 自动重试 |
| `data/MediaRepository.kt` | ✅ 已存在 | provider/credentials 管理, search→categoryVideos 兼容包装 |
| `data/BilibiliApi.kt` | ✅ 已存在 | WBI 签名 + view/playurl 解析, 支持多P、Cookie 注入 |
| `data/BilibiliWbiSigner.kt` | ✅ 已存在 | 独立 WBI 签名算法 |
| `data/HttpExtensions.kt` | ✅ 已存在 | isTransientHttpStatusCode + 中文错误提示 |
| `net/AppHttpClient.kt` | ✅ 已存在 | OCSP soft-fail TLS, Media3 HttpsURLConnection 默认安装 |
| `log/AppLog.kt` | ✅ 已存在 | 文件滚动日志 + logcat, 敏感信息脱敏 |
| `player/CappedLoadErrorHandlingPolicy.kt` | ✅ 已存在 | Media3 版, HTTP 错误不重试 |
| `player/TolerantDashManifestParser.kt` | ✅ 已存在 | DASH 非数字 id 兼容 |
| `update/AppUpdateManager.kt` | ✅ 已存在 | PackageInstaller/ACTION_INSTALL_PACKAGE/ACTION_VIEW 三级回退 |
| `MediaCenterApp.kt` | ✅ 已存在 | Application 类, AppLog init + crash handler |

## 构建结果

```
BUILD SUCCESSFUL in 3s
```

## 提交

无新提交——所有文件已在先前提交中（最早可追溯到 `2be0d00` 初始导入）。

## 关注点

- `AppUpdateResult` 和 `InstallLaunchResult` 定义在 `update/AppUpdateManager.kt` 同一文件中，未拆独立文件（与原 `MainActivity.kt` 引用方式一致，功能等价）
- 正常模块使用 Media3 (`androidx.media3`)，与 legacy 模块的 ExoPlayer 2.x (`com.google.android.exoplayer2`) 区分正确
- `CappedLoadErrorHandlingPolicy` 已适配 Media3 API（`DefaultLoadErrorHandlingPolicy` 构造函数签名的差异）
- 所有 `MainActivity.kt` 中的 import 已指向提取后的独立文件
- AndroidManifest.xml 已引用 `.MediaCenterApp`
