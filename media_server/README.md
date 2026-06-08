# Media Server

合规公开视频聚合服务。公开 API 只返回 Android TV App 可直接播放的普通视频点播地址，不要求客户端知道视频来自 Bilibili、YouTube 或其他 Provider。

## 视频类型

- `vod`：普通视频 / 点播视频。当前唯一支持的播放类型。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

## API

- `GET /health`
- `GET /api/categories`
- `GET /api/categories/:id/videos?page=1&pageSize=20`
- `GET /api/search?q=关键词&page=1&pageSize=20`
- `GET /api/videos/:id`
- `GET /api/videos/:id/playback`

在 workwork 的 nginx 反代后，公开路径使用服务分类前缀 `/api/media/*`，例如 `/api/media/categories` 会转发到本服务内部的 `/api/categories`。nginx 会设置 `X-Forwarded-Prefix: /api/media`，因此响应里的图片和播放代理地址也会生成到 `/api/media/asset`。

分类浏览走 `/api/categories/:id/videos`，结果由服务端定期维护缓存快照，默认预刷新 100 页；客户端请求超过当前缓存深度时，服务端会继续扩展该分类的视频流。

`/api/search` 只用于搜索页的临时关键词搜索，不缓存响应。分类和搜索接口都会先做后端点播播放地址校验，只公开后续能通过 `/api/videos/:id/playback` 拿到 `kind=vod` 的结果。App 不需要也不应该处理 provider、sourceUrl、网页播放或外部打开。

内置一级专区为电影、电视剧、纪录片、红色经典和 Dota 专区。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 录像和完整对局为主，兼顾 Dota2 / TI。

## 配置与部署

- 本地开发默认直接读取内置 `src/contentCategories.json`，监听 `http://127.0.0.1:8787`，不需要 nginx。
- 线上由 workwork admin 管理分类和代理设置。admin 会生成运行时分类文件并把它挂载到容器内 `/config/contentCategories.json`，服务端通过 `CONTENT_CATEGORIES_FILE=/config/contentCategories.json` 读取。
- 如果不通过 workwork，也可以手动设置 `CONTENT_CATEGORIES_FILE=/path/to/contentCategories.json`（兼容别名 `MEDIA_SERVER_CONTENT_CATEGORIES_FILE`）覆盖内置分类；JSON 结构与 `src/contentCategories.json` 相同。
- `HOST` / `PORT` 控制服务监听地址；nginx 公开前缀由反代设置 `X-Forwarded-Prefix`，本服务会据此重写图片和播放代理 URL。

## 点播解析与流式播放

- Bilibili 普通视频优先走已有公开接口解析 MP4 点播地址。
- YouTube 普通视频通过开源工具 `yt-dlp` 解析点播地址；本地运行请安装 `yt-dlp`，或设置 `VOD_EXTRACTOR_BIN=/path/to/yt-dlp`。
- `/api/asset` 不会先下载完整文件，而是把 App 的 HTTP Range 请求直接转发给上游 CDN，再把响应流式返回给 Media3/ExoPlayer，支持边下边播和 seek。
- 后端会按 URL + Range 缓存点播片段和图片响应，默认缓存目录 `/cache`，总上限 50GB，可用 `BACKEND_CACHE_DIR` 和 `BACKEND_CACHE_MAX_BYTES` 覆盖。
- 上游 HTTP 请求默认 10 秒超时（`PROVIDER_FETCH_TIMEOUT_MS`），Provider 搜索默认 15 秒超时（`PROVIDER_SEARCH_TIMEOUT_MS`），搜索页候选详情校验默认 8 秒超时（`SEARCH_DETAIL_TIMEOUT_MS`），直接详情/点播解析默认 35 秒超时（`DETAIL_RESOLVE_TIMEOUT_MS`），避免单个来源卡住整个 API。

后续扩展其他来源时，请通过官方 API、已授权数据源或合规开源解析工具实现 `VideoProvider`，不要接入盗版站点、绕过 DRM 或未授权下载。
