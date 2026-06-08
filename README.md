# Media Center Android App

这个仓库只保留 Android TV 客户端：

- `media_app`：Android TV + Kotlin + Jetpack Compose + Media3 客户端，针对遥控器焦点、电视安全边距和 2 米观看距离优化。

服务端实现已经迁移到 `workwork/services/media_server`。App 只需要一个服务端地址，默认是 `https://yinghuoren.com/api/media`，也可以通过 `MEDIA_SERVER_URL` 覆盖。

## 视频类型

项目把视频分成两类：

- `vod`：普通视频 / 点播视频。当前只支持这一类。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

服务端默认一级专区为电影、电视剧、纪录片、红色经典和 Dota 专区。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 对局录像为主，兼顾 Dota2 和 TI 赛事。

公开 API 不暴露具体视频来源；后端负责搜索、整理普通视频，并解析点播播放地址。搜索结果会先经过后端点播播放地址校验，只返回可由 App 内播放器播放的 `vod`。播放地址由服务端返回，播放器可以边下边播和 seek，不需要等整个文件下载完成。

## 编译 Android TV 客户端

项目已配置 Gradle Wrapper。建议使用 Android Studio 自带 JBR 和 Android Studio 管理的 SDK：

```bash
cd media_app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

如需覆盖默认服务端 URL：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=https://your-domain.example/api/media
```

## 工具链约定

JS/Web 前端项目优先使用 Bun（`bun install`、`bun run`、`bun test`）。当前 `media_app` 是 Android TV 原生客户端，构建仍使用 Gradle/Android Studio 工具链；如果后续新增 Web 管理台或 H5 前端，应默认采用 Bun。

## API 契约

API 由 `workwork/services/media_server` 维护。实现或调整 App 前先看：

- `workwork/services/media_server/API.md`
- `workwork/services/media_server/openapi.json`

扩展其他 Provider 时应使用官方 API 或已授权数据源，不接入盗版站点、DRM 绕过或盗链资源。
