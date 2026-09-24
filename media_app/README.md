# Android TV App（normal 变体）

这个目录是 Android TV 客户端的 **normal 变体**：Kotlin + Jetpack Compose + Media3（minSdk 23）。服务端实现见 `../media_server`。App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。

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

B 站单视频（bvid）可能包含多个分 P（如 P1/P2/P3）。App 自动识别多 P：

- **列表预取**：浏览分类时后台解析播放地址，同时也缓存页数信息
- **详情页**：进入详情后自动解析首页播放地址
- **播放页按"下"**：多 P 视频先依次翻页（P1→P2→P3），播完所有 P 后跳转列表下一个视频
- **单 P 视频**：按"下"直接跳到列表下一个视频

播放地址不落盘；每次播放前调用 `BilibiliApi.resolvePlayback` 获取最新直链（WBI 签名 + view + playurl，客户端本地解析）。

## 服务端 URL 覆盖

在 Android Studio 里可以把 `MEDIA_SERVER_URL=https://your-domain.example/api/media` 写入 `media_app/gradle.properties` 或用户级 `~/.gradle/gradle.properties`，然后重新 Sync。`MEDIA_SERVER_URL` 是 App 唯一需要的服务端配置；可以是服务根地址，也可以直接带 API 路径。API 契约由 `../media_server/API.md` 维护。

首页从服务端读取内容分类，默认展示服务端返回的第一个分类（专区清单以服务端下发为准，见 `../media_server/src/contentCategories.json`）。App 只消费服务端返回的统一 opaque 视频 ID、opaque 播放选项 ID、`videoKind=vod` 和点播播放地址，并原样播放服务端返回的图片/播放 URL；不处理直播、视频来源、网页播放、外部打开或代理 URL 签名。

播放器使用 Media3 `DefaultHttpDataSource` 直接流式读取播放 URL；客户端不使用下载器或落盘媒体缓存。B 站 CDN 播放流与图床（`*.hdslb.com`）需要带 PC UA + Referer 请求头，由 `PlaybackService` 的 `BILI_CDN_HEADERS` 与 Coil 的 `BilibiliImageLoaderFactory` 统一注入（见 `app/src/main/java/com/example/mediacenter/net/BilibiliImageLoaderFactory.kt`）。

## 应用内更新

启动后 App 会请求 `/app/update?versionCode=当前版本&variant=normal` 检查更新；发现新版本时下载 APK、校验 SHA-256，然后拉起系统安装器（PackageInstaller → ACTION_INSTALL_PACKAGE → ACTION_VIEW 三级回退）。Android 8+ 未开启"安装未知应用"权限时引导用户去系统设置开启。普通 Android TV 应用不能绕过系统权限静默安装，首次需要用户允许"安装未知应用"并确认安装。

## 其他特性

- **断网自动恢复**：首页/播放页在断网恢复后自动重试（`NetworkRestoreEffect`，API 24+ 用默认网络回调，API 23 降级为 registerNetworkCallback）。
- **断点续播**：单 P 视频记住上次播放位置，7 天内回到近末尾（15s 内）视为看完。
- **崩溃上报**：未捕获异常写入本地文件，下次启动上报 `POST /api/crash/report`。
- **设置页**：版本信息、清空续播记录/更新缓存/日志、查看最近日志。
