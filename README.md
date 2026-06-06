# Media Center

一个合规公开视频媒体中心原型，包含：

- `media_server`：Node.js/TypeScript 服务端，聚合 Bilibili / YouTube 方向的公开视频内容，提供搜索、详情和点播播放 API。
- `media_app`：Android TV 客户端，使用 Compose 大屏 UI 和 Media3 播放器，针对遥控器焦点、电视安全边距和 2 米观看距离优化。

## 运行服务端

```bash
cd media_server
npm install
npm run build
npm start
```

默认监听 `http://localhost:8787`。

## 视频类型

项目把视频分成两类：

- `vod`：普通视频 / 点播视频。当前只支持这一类。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

服务端内置一级专区为电影、电视剧、纪录片、红色经典和 Dota 专区。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 对局录像为主，兼顾 Dota2 和 TI 赛事。

公开 API 不暴露具体视频来源；后端负责从 Bilibili / YouTube 搜索、整理普通视频，并解析点播播放地址。搜索结果会先经过后端点播播放地址校验，只返回可由 App 内播放器播放的 `vod`。播放通过 `/api/asset` 做 Range 代理，播放器可以边下边播和 seek，不需要等整个文件下载完成。

YouTube 普通视频通过可选的开源工具 `yt-dlp` 解析点播播放地址；本地运行如需启用 YouTube 可播放结果，请先安装 `yt-dlp`，或通过 `VOD_EXTRACTOR_BIN` 指向兼容工具。所有扩展都应只处理公开视频和授权内容，不接入盗版站点、DRM 绕过或未授权下载。

## 编译 Android TV 客户端

项目已配置 Gradle Wrapper。建议使用 Android Studio 自带 JBR 和 Android Studio 管理的 SDK：

```bash
cd media_app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

如需连接真机或局域网服务端，可在编译时覆盖：

```bash
./gradlew :app:assembleDebug -PMEDIA_SERVER_URL=http://服务端IP:8787
```

## 工具链约定

JS/Web 前端项目优先使用 Bun（`bun install`、`bun run`、`bun test`）。当前 `media_app` 是 Android TV 原生客户端，构建仍使用 Gradle/Android Studio 工具链；如果后续新增 Web 管理台或 H5 前端，应默认采用 Bun。

## API 契约

- `GET /api/categories`：返回五个一级分类，不包含内部搜索词。
- `GET /api/search?q=关键词&category=dota-zone&page=1&pageSize=20`：返回已验证可播放的 `vod` 视频摘要。
- `GET /api/videos/:id`：返回视频详情和播放选项。
- `GET /api/videos/:id/playback`：返回 App 可直接播放的点播媒体地址。

扩展其他 Provider 时应使用官方 API 或已授权数据源，不接入盗版站点、DRM 绕过或盗链资源。
