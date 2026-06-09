# Media Server

Go 实现的媒体聚合服务。当前只聚合 **Bilibili**，面向 Android TV App 暴露分类、搜索、详情和播放选项 API。

## 当前架构

- 后端的职责是：**聚合元数据、生成不透明视频 ID、补充 App 直连上游所需请求头**。
- 对 App **可直接访问**的国内资源，后端**不默认反代媒体流量**；`thumbnailUrl` 和 `playbackOptions[].url` 会直接返回上游 URL。
- App 必须把响应里的 `thumbnailHeaders` / `playbackOptions[].headers` 原样带到图片和播放请求里。
- 后续若启用海外/受限来源，可以继续沿用同一套 API：对 App 不能直连的资源返回后端 URL，对能直连的资源返回直连 URL。

## API

完整 App 对接说明见 [`API.md`](./API.md)。

- `GET /health`
- `GET /api/health`
- `GET /api/categories`
- `GET /api/categories/:id/videos?page=1&pageSize=20`
- `GET /api/search?q=关键词&page=1&pageSize=20`
- `GET /api/videos/:id`
- `GET /api/videos/:id/playback`

在 workwork 的 nginx 反代后，公开路径仍然是 `/api/media/*`；例如 App 请求 `/api/media/categories`，nginx 会转发到本服务内部的 `/api/categories`。

## 配置与部署

- `CONTENT_CATEGORIES_FILE`：运行时分类配置。workwork admin 会生成并挂载到 `/config/contentCategories.json`。
- `PROVIDER_FETCH_TIMEOUT_MS`：Bilibili 元数据和 `playurl` 请求超时，默认 15 秒。
- `MEDIA_SERVER_SIGNING_KEY`：用于签发不透明视频 ID / 播放选项 ID。未配置时启动时随机生成；也兼容旧变量 `MEDIA_ASSET_SIGNING_KEY` 作为 fallback。
- `HOST` / `PORT`：监听地址，默认 `0.0.0.0:8787`。

## 当前来源策略

- 当前配置只启用 **bilibili**。
- 分类/搜索结果会做关键词、排除词、时长与标题规则过滤，尽量保留完整长视频、减少解说/混剪/短片污染。
- 播放选项当前返回 **Bilibili 直连 MP4 URL**；后端在播放前实时刷新 URL，避免客户端长期缓存失效地址。
