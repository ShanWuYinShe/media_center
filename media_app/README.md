# Android TV App

这个目录只包含 Android TV 客户端。服务端实现已经迁移到 `workwork/services/media_server`。App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。

## Android Studio 菜单工作流

用 Android Studio 直接打开 `media_app/` 目录，等待 Gradle Sync 完成后即可通过菜单操作，不需要手动调用 `gradlew`。

1. 编译：使用 **Build > Make Project**。
2. 运行：选择工具栏的 `app` 运行配置，然后使用 **Run > Run 'app'**。
3. 打包调试 APK：使用 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. 打包发布 APK：使用 **Build > Generate Signed Bundle / APK...**。

App 现在按 Android 4.4.2/API 19 作为主目标设计，使用单一原生 View UI，不再维护 API 21+ 专用 Compose 变体，也不打包 Compose、Coil 2.x、TV Material 等 API 21+ 依赖。

如需覆盖默认服务端 URL：

在 Android Studio 里可以把 `MEDIA_SERVER_URL=https://your-domain.example/api/media` 写入 `media_app/gradle.properties` 或用户级 `~/.gradle/gradle.properties`，然后重新 Sync。

`MEDIA_SERVER_URL` 是 App 唯一需要的服务端配置；可以是服务根地址，也可以直接带 API 路径。API 契约由 `workwork/services/media_server/API.md` 和 `workwork/services/media_server/openapi.json` 维护。

首页会从服务端读取内容分类，默认展示服务端返回的第一个分类。内置一级专区包括电影、电视剧、纪录片、红色经典和 Dota 专区。App 只消费服务端返回的统一 opaque 视频 ID、opaque 播放选项 ID、`videoKind=vod` 和点播播放地址，并原样播放服务端返回的图片/播放 URL；不处理直播、视频来源、网页播放、外部打开或代理 URL 签名。

播放前调用 BilibiliApi.resolvePlayback 获取最新播放地址，播放器使用 Media3 `DefaultHttpDataSource` 直接流式读取服务端返回的 URL；客户端不使用下载器或落盘媒体缓存。

启动后 App 会请求 `/app/update?versionCode=当前版本` 检查更新；发现新版本时自动下载 APK、校验 SHA-256，然后拉起系统安装器。普通 Android TV 应用不能绕过系统权限静默安装，首次需要用户允许“安装未知应用”并确认安装。
