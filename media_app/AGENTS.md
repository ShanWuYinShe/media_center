# media_app（Android TV 客户端）

`media_center` 仓库的 Android TV 客户端：Kotlin + Jetpack Compose + Media3。仓库级结构与跨端契约见上级 [AGENTS.md](../AGENTS.md)。

通用约束（Git 规范、Commit 格式、验证流程、JDK 配置等）以全局 `~/AGENTS.md` 为准，本文件只做项目特有补充。

## 构建

```bash
cd media_app
# JAVA_HOME 见 skill local-environment（Android Studio JBR 路径；以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加（原因见 skill toolchain-setup：Daemon 构建后 idle 数小时不退出，各占 -Xmx2g）
# release（非 debug）：R8 minify + 资源压缩，体积减半且可直接安装。
# 正式发版由 GitHub Actions 在 tag 上构建并注入固定发布签名（见根 AGENTS.md）；
# 本地构建未注入 ANDROID_SIGNING_* 时回退 debug 签名，仅用于调试。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 版本号与发版、部署 APK

tag 驱动版本号、双变体 APK 构建与 manifest 部署流程见 skill media-app-release。

正式发版统一由 GitHub Actions 完成：push `vX.Y.Z` tag → `.github/workflows/release-app.yml` 构建 normal + legacy release APK、发布 GitHub Release、可选自动部署到服务器；本地 `assembleRelease` 只用于调试。发版 secrets、签名与自动部署配置见仓库根 AGENTS.md「APK 发版（GitHub Actions）」。

## TV UI/UX 设计约定（详见 skill media-app-tv-ui）

TV 交互与视觉约定见 skill media-app-tv-ui。

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 播放补充：YouTube 1080P+ 的分离音轨用 `MergingMediaSource` 合流；B 站多段 durl 用 `ConcatenatingMediaSource` 顺序拼接（MediaController 只能传 MediaItem，额外地址经 MediaMetadata extras 传给 PlaybackService 的 `PlaybackMediaSourceFactory`）。
- 默认清晰度：自动选档按「屏幕物理高度与设备档位取小」收敛（`data/PlaybackTuning`，低端机 ≤720P），手动切清晰度不受限；离开播放页后播放服务保活 20s（`player/PlaybackServiceKeepAlive`），快速回看不重建服务/播放器。
- 服务端地址：默认 `https://media.chaoset.com/api/v1`；如需覆盖，设置 `MEDIA_SERVER_URL`。

## 代码结构约定

- ViewModel 依赖 `MediaRepositoryApi` 接口（`data/MediaRepositoryApi.kt`），单测用 fake 注入，不依赖 OkHttp/SharedPreferences。
- HTTP 调用统一 `data/HttpCall.kt` 的 `Call.await()`；响应体读取必须在 IO 调度器。
- 通用 TV 控件集中在 `ui/components/`（`TvActionButton`、`TvChip`、`TvDialogButton`、`PagedVideoGrid`、`VideoCard` 等），页面不要重复按钮样式或分页网格。
- 播放器：`PlayerViewModel` 负责解析/清晰度/分P/合集 next 决策；`PlayerControllerConnection` 负责 MediaController 重连与幂等释放；`PlayerAudioFocus` 负责音频焦点；`PlayerContent` 只编排播放与覆盖层。
- 首页由 `HomeViewModel` 一次只加载当前分类（10 分钟缓存 + 分页；冷启动先用 `filesDir` 快照 SWR 渲染，缓存过期也先展示旧内容再后台刷新），分类切换由 `HomeCategoryTabs` 焦点触发；「继续观看」读本地 `WatchHistory`。UI 不做磁盘 IO。
