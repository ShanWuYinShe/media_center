# media_app（Android TV 客户端）

`media_center` 仓库的 Android TV 客户端：Kotlin + Jetpack Compose + Media3。仓库级结构与跨端契约见上级 [AGENTS.md](../AGENTS.md)。

通用约束（Git 规范、Commit 格式、验证流程、JDK 配置等）以全局 `~/AGENTS.md` 为准，本文件只做项目特有补充。

## 构建

```bash
cd media_app
# JAVA_HOME 见 skill local-environment（Android Studio JBR 路径；以 ls 验证，变动时用 mdfind 定位）
# --no-daemon 必加（原因见 skill toolchain-setup：Daemon 构建后 idle 数小时不退出，各占 -Xmx2g）
# release（非 debug）：已配 debug 签名（signingConfig=debug）+ R8 minify，体积减半且可直接安装。
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease --no-daemon
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 版本号与发版、部署 APK（详见 skill media-app-release）

tag 驱动版本号、双变体 APK 构建与 manifest 部署流程见 skill media-app-release。

## TV UI/UX 设计约定（详见 skill media-app-tv-ui）

TV 交互与视觉约定见 skill media-app-tv-ui。

## 技术栈与服务端地址

- Compose + TV Material + Media3 ExoPlayer；网络：OkHttp；序列化：kotlinx-serialization；图片：Coil。
- 播放补充：YouTube 1080P+ 的分离音轨用 `MergingMediaSource` 合流；B 站多段 durl 用 `ConcatenatingMediaSource` 顺序拼接（MediaController 只能传 MediaItem，额外地址经 MediaMetadata extras 传给 PlaybackService 的 `PlaybackMediaSourceFactory`）。
- 服务端地址：默认 `https://media.chaoset.com/api/v1`；如需覆盖，设置 `MEDIA_SERVER_URL`。

## 代码结构约定

- ViewModel 依赖 `MediaRepositoryApi` 接口（`data/MediaRepositoryApi.kt`），单测用 fake 注入，不依赖 OkHttp/SharedPreferences。
- HTTP 调用统一 `data/HttpCall.kt` 的 `Call.await()`；响应体读取必须在 IO 调度器。
- 通用 TV 控件集中在 `ui/components/`（`TvActionButton`、`TvChip`、`TvDialogButton`、`PagedVideoGrid`、`VideoCard` 等），页面不要重复按钮样式或分页网格。
- 播放器：`PlayerViewModel` 负责解析/清晰度/分P/合集 next 决策；`PlayerControllerConnection` 负责 MediaController 重连与幂等释放；`PlayerAudioFocus` 负责音频焦点；`PlayerContent` 只编排播放与覆盖层。
- 首页展示数据由 `HomeViewModel` + `buildHomeContent` 纯函数装配；UI 不做观看历史磁盘 IO。
