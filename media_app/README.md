# Android TV App（normal 变体）

这个目录是 Android TV 客户端的 **normal 变体**：Kotlin + Jetpack Compose + Media3（minSdk 23）。服务端实现见 `../media_server`。App 只需要一个服务端地址，默认是 `https://media.chaoset.com/api/v1`，也可以通过 `MEDIA_SERVER_URL` 覆盖。

> 另有 `../media_app_legacy`（legacy 变体：Views + ExoPlayer 2.x，minSdk 19，适配更老的国产 TV 盒子）。两者相互独立，各自维护。

## Android Studio 菜单工作流

用 Android Studio 直接打开 `media_app/` 目录，等待 Gradle Sync 完成后即可通过菜单操作，不需要手动调用 `gradlew`。

1. 编译：使用 **Build > Make Project**。
2. 运行：选择工具栏的 `app` 运行配置，然后使用 **Run > Run 'app'**。
3. 打包调试 APK：使用 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. 打包发布 APK：使用 **Build > Generate Signed Bundle / APK...**。

命令行构建（JAVA_HOME 见 skill local-environment 的 Android Studio JBR 条目）：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## B 站多 P 视频支持

B 站单视频（bvid）可能包含多个分 P（如 P1/P2/P3）：

- **目录聚合**：catalog 导入时把 `_pN` 分 P 条目聚合为一条基础视频（时长累加），列表不会出现重复条目。
- **播放页切 P**：解析 `view` 拿到全部 pages，控制条的"分P"行按页切换，切页时重新解析该页直链。
- **按页续播**：断点按 `videoId#pN` 隔离存储；"继续观看"记录最后观看页，点击直达该页。
- 个别老视频的播放地址由多段 `durl` 组成：App 保留全部分段并顺序拼接播放（`ConcatenatingMediaSource`），不再只播第一段。
- 不做列表预取、不自动连播下一个视频；播放地址不落盘，每次播放/切清晰度按需调用 `BilibiliApi.resolvePlayback`（WBI 签名 + view + playurl，客户端本地解析）。

## 服务端 URL 覆盖

在 Android Studio 里可以把 `MEDIA_SERVER_URL=https://your-domain.example/api/media` 写入 `media_app/gradle.properties` 或用户级 `~/.gradle/gradle.properties`，然后重新 Sync。`MEDIA_SERVER_URL` 是 App 唯一需要的服务端配置；可以是服务根地址，也可以直接带 API 路径。API 契约由 `../media_server/API.md` 维护。

首页从服务端读取 provider 与分类（分类顺序以服务端 `contentCategories.json` 编排为准），App 只消费 providers / categories / videos 三个目录接口。`VideoSummary.id` 是 nativeId：bilibili 由 App 本地解析播放，youtube 调 server 的 `/providers/youtube/resolve`（1080P+ 会返回分离的音轨，由播放服务用 `MergingMediaSource` 合流）。App 不处理直播、网页播放或外部打开。

播放器使用 Media3 `DefaultHttpDataSource` 直接流式读取播放 URL；客户端不使用下载器或落盘媒体缓存。B 站 CDN 播放流与图床（`*.hdslb.com`）需要带 PC UA + Referer 请求头，由 `PlaybackService` 的 `BILI_CDN_HEADERS` 与 Coil 的 `BilibiliImageLoaderFactory` 统一注入（见 `app/src/main/java/com/example/mediacenter/net/BilibiliImageLoaderFactory.kt`）。

## 应用内更新

启动后 App 会请求 `/app/update?versionCode=当前版本&variant=normal` 检查更新；发现新版本时下载 APK、校验 SHA-256，然后拉起系统安装器（PackageInstaller → ACTION_INSTALL_PACKAGE → ACTION_VIEW 三级回退）。Android 8+ 未开启"安装未知应用"权限时引导用户去系统设置开启。普通 Android TV 应用不能绕过系统权限静默安装，首次需要用户允许"安装未知应用"并确认安装。

## 界面与功能要点

- **首页**：TopBar + Hero（有继续观看时优先"接着看"，否则精选首条）+ 继续观看行 + 分类行；首次加载使用骨架屏，空内容与网络错误使用不同的空态组件。
- **分类浏览**：服务端分页 + 全部/短片/中片/长片时长筛选（筛选在服务端完成，不是在已加载页上过滤）。
- **搜索**：目录搜索分页加载；YouTube 走实时 ytsearch；限流/网络错误分别提示。
- **播放器**：播放/暂停、±10 秒、可聚焦进度条（左右键 10 秒步进）、分P/清晰度切换；YouTube 音视频分离直链在服务内合流播放。
- **断网自动恢复**：首页/播放页在断网恢复后自动重试（`NetworkRestoreEffect`，API 24+ 用默认网络回调，API 23 降级为 registerNetworkCallback）。
- **断点续播**：记住上次播放位置（多 P 视频按页隔离），7 天内回到近末尾（15s 内）视为看完。
- **继续观看**：观看历史按设备本地保存（最近 20 条，看完自动移除），首页顶部聚合为一行，卡片带观看进度条；多 P 视频记录最后观看页，点击直达该页。仅显示当前视频源的条目。设置页可清除。
- **崩溃上报**：未捕获异常写入本地文件，下次启动上报 `POST /api/crash/report`。
- **设置页**：版本信息、清空续播记录/观看历史/更新缓存/日志、查看最近日志。
