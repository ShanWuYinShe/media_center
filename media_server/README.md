# Media Server

Go 实现的媒体聚合服务。支持 **Bilibili**、**YouTube**、**Internet Archive** 来源，面向 Android TV App 暴露分类、搜索、详情、播放选项和签名资源代理 API。

## 当前架构

- 后端的职责是：**聚合多来源元数据、生成不透明视频 ID、刷新播放地址，并按来源选择 direct/proxy delivery**。
- `direct` 来源（默认 Bilibili）返回上游 URL + companion headers，由 App 直连上游。
- `proxy` 来源（默认 YouTube / Internet Archive）返回 `/api/asset` 签名代理地址，上游 companion headers 会被签入代理 payload，不下发给 App。
- `/api/asset` 只接受服务端签名的 HTTP(S) 上游 URL，支持 GET/HEAD、Range 和条件请求。

## API

完整 App 对接说明见 [`API.md`](./API.md)。

- `GET /health`
- `GET /api/health`
- `GET /api/categories`
- `GET /api/categories/:id/videos?page=1&pageSize=20`
- `GET /api/search?q=关键词&page=1&pageSize=20`
- `GET /api/videos/:id`
- `GET /api/videos/:id/playback`
- `GET /api/asset?...`

在 workwork 的 nginx 反代后，公开路径仍然是 `/api/media/*`；例如 App 请求 `/api/media/categories`，nginx 会转发到本服务内部的 `/api/categories`。

## 配置与部署

- `CONTENT_CATEGORIES_FILE`：运行时来源/分类配置。workwork admin 会生成并挂载到 `/config/contentCategories.json`。
- `PROVIDER_FETCH_TIMEOUT_MS`：上游元数据和播放地址请求超时，默认 15 秒。
- `MEDIA_SERVER_SIGNING_KEY`：用于签发不透明视频 ID / 播放选项 ID。未配置时启动时随机生成；也兼容旧变量 `MEDIA_ASSET_SIGNING_KEY` 作为 fallback。
- `VOD_EXTRACTOR_URL`：内部点播解析服务地址；启用 `youtube` 来源时必需。
- `YOUTUBE_API_KEY`：YouTube Data API key；启用 `youtube` 来源时必需。
- `MEDIA_SERVER_PUBLIC_URL`：可选，对外可见 API 基址（如 `https://yinghuoren.com/api/media`），用于签发 proxy asset 绝对 URL。
- `MEDIA_SERVER_ASSET_URL_TTL_SECONDS`：可选，proxy asset URL 有效期，默认 21600 秒。
- `MEDIA_ASSET_CACHE_DIR`：proxy asset 磁盘缓存目录，默认 `/cache`；设为空可关闭缓存。
- `MEDIA_ASSET_CACHE_MAX_BYTES`：proxy asset 磁盘缓存上限，默认 50GB。
- `HOST` / `PORT`：监听地址，默认 `0.0.0.0:8787`。

## 当前来源策略

- 默认未声明 `sources` 时启用 **bilibili**，也可在 contentCategories.json 中启用 `youtube` / `internet-archive`。
- 分类可用通用 `searchQuery`，也可通过 `sourceQueries` 为不同来源指定不同查询词。
- 分类/搜索结果会做关键词、排除词、时长与标题规则过滤，尽量保留完整长视频、减少解说/混剪/短片污染。
- 播放选项会在详情/播放接口实时刷新；direct 来源返回上游地址，proxy 来源返回签名 asset proxy URL，避免客户端长期缓存失效地址。
