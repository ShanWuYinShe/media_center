# Media Server

合规公开视频聚合服务。公开 API 只返回 Android TV App 可直接播放的普通视频点播地址，不要求客户端知道视频来自 Bilibili、YouTube 或其他 Provider。

## 视频类型

- `vod`：普通视频 / 点播视频。当前唯一支持的播放类型。
- `live`：直播视频。当前不搜索、不整理、不解析、不播放。

## API

- `GET /health`
- `GET /api/categories`
- `GET /api/search?q=关键词&page=1&pageSize=20`
- `GET /api/search?category=documentaries&page=1&pageSize=20`
- `GET /api/videos/:id`
- `GET /api/videos/:id/playback`

`/api/search` 会先做后端点播播放地址校验，只公开后续能通过 `/api/videos/:id/playback` 拿到 `kind=vod` 的结果。App 不需要也不应该处理 provider、sourceUrl、网页播放或外部打开。

内置一级专区为电影、电视剧、纪录片、红色经典和 Dota 专区。Dota 分类以 Dota1 / 魔兽争霸 3 / DotA Allstars 录像和完整对局为主，兼顾 Dota2 / TI。

## 点播解析与流式播放

- Bilibili 普通视频优先走已有公开接口解析 MP4 点播地址。
- YouTube 普通视频通过开源工具 `yt-dlp` 解析点播地址；本地运行请安装 `yt-dlp`，或设置 `VOD_EXTRACTOR_BIN=/path/to/yt-dlp`。
- `/api/asset` 不会先下载完整文件，而是把 App 的 HTTP Range 请求直接转发给上游 CDN，再把响应流式返回给 Media3/ExoPlayer，支持边下边播和 seek。
- 后端会按 URL + Range 缓存点播片段和图片响应，默认缓存目录 `/cache`，总上限 50GB，可用 `BACKEND_CACHE_DIR` 和 `BACKEND_CACHE_MAX_BYTES` 覆盖。

后续扩展其他来源时，请通过官方 API、已授权数据源或合规开源解析工具实现 `VideoProvider`，不要接入盗版站点、绕过 DRM 或未授权下载。
